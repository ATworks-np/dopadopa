import os
import re
from datetime import datetime, timezone, timedelta
from uuid import uuid4
from fastapi import FastAPI, Depends, HTTPException, Request, Header
from fastapi.responses import JSONResponse
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pathlib import Path
from pydantic import BaseModel, ConfigDict, Field
from server.auth import authenticated_user
from server.models import SessionBatch, FeedbackInput
from server.store import MemoryStore, FirestoreStore, ConflictError

app = FastAPI(title="DopaGuard", version="0.1.0")
WEB = Path(__file__).resolve().parent / "web"
app.mount("/assets", StaticFiles(directory=WEB), name="assets")

@app.get("/", include_in_schema=False)
def web_demo():
    return FileResponse(WEB / "index.html")

@app.exception_handler(ConflictError)
async def store_conflict(request, exception):
    return JSONResponse({"detail": "data changed or deletion in progress; retry after confirming consent"}, status_code=409)

def get_store():
    if not hasattr(app.state, "store"):
        if os.environ.get("STORE_MODE") == "memory":
            if os.environ.get("K_SERVICE"): raise RuntimeError("memory store prohibited on Cloud Run")
            app.state.store = MemoryStore()
        else: app.state.store = FirestoreStore(os.environ["GOOGLE_CLOUD_PROJECT"])
    return app.state.store

@app.middleware("http")
async def limit_body(request: Request, call_next):
    # Limit streamed/chunked input as well as Content-Length.
    if request.method in {"POST", "PUT", "PATCH"}:
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > 300_000: return JSONResponse({"detail": "request too large"}, status_code=413)
        request._body = bytes(data)
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Content-Security-Policy"] = "default-src 'self'; connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    response.headers["Referrer-Policy"] = "no-referrer"
    return response

def valid_id(value):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value): raise HTTPException(422, "invalid ID")
    return value

def owned(store, uid, kind, key):
    valid_id(key)
    try: return store.get(uid, kind, key)
    except KeyError: raise HTTPException(404, "record not found") from None

@app.get("/healthz")
@app.get("/health")
def health(): return {"status": "ok"}

@app.get("/v1/demo/sessions")
def demo_sessions():
    from server.samples import SCENARIOS, sample_batch
    return [{"scenario": key, "label": label, "data_origin": "sample", "session": sample_batch(key).model_dump(mode="json")} for key, label in SCENARIOS.items()]

@app.post("/v1/demo/sessions/{scenario}/import")
def import_sample(scenario: str, uid=Depends(authenticated_user), store=Depends(get_store),
    cloud_generation: int = Header(default=0, ge=0, alias="X-DopaGuard-Generation")):
    from server.samples import sample_batch
    try: batch = sample_batch(scenario)
    except KeyError: raise HTTPException(404, "sample scenario not found") from None
    return register(batch, uid, store, cloud_generation)

@app.get("/v1/user/generation")
def user_generation(uid=Depends(authenticated_user), store=Depends(get_store)):
    return {"generation": store.generation(uid)}

@app.post("/v1/sessions:batch")
def register(batch: SessionBatch, uid=Depends(authenticated_user), store=Depends(get_store),
    cloud_generation: int = Header(default=0, ge=0, alias="X-DopaGuard-Generation")):
    generation = store.generation(uid)
    if cloud_generation != generation:
        raise HTTPException(409, "cloud generation changed; confirm consent before a new sync")
    value = batch.model_dump(mode="json")
    value["events"] = sorted(value["events"], key=lambda e: (datetime.fromisoformat(e["timestamp"]), e["event_id"]))
    # No raw content in logs. Collection origin and consent are immutable on replay.
    try: store.put(uid, "sessions", batch.session_id, value, immutable=True, expected_generation=generation)
    except ConflictError: raise HTTPException(409, "session already exists with different content") from None
    return {"session_id": batch.session_id, "event_count": len(batch.events), "data_origin": batch.events[0].data_origin}

@app.get("/v1/sessions")
def sessions(uid=Depends(authenticated_user), store=Depends(get_store)):
    return [{"session_id": s["session_id"], "data_origin": s["events"][0]["data_origin"], "event_count": len(s["events"])} for s in store.list(uid, "sessions")]

@app.get("/v1/sessions/{session_id}")
def timeline(session_id: str, uid=Depends(authenticated_user), store=Depends(get_store)):
    return owned(store, uid, "sessions", session_id)

@app.get("/v1/sessions/{session_id}/analyses")
def session_analyses(session_id: str, uid=Depends(authenticated_user), store=Depends(get_store)):
    owned(store, uid, "sessions", session_id)
    return [{key: value.get(key) for key in ("analysis_id", "status", "data_origin", "created_at")}
        for value in store.list(uid, "analyses") if value["session_id"] == session_id]

class AnalyzeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")

@app.post("/v1/analyses")
def analyze(body: AnalyzeInput, uid=Depends(authenticated_user), store=Depends(get_store),
            authorization: str | None = Header(default=None)):
    generation = store.generation(uid)
    session = owned(store, uid, "sessions", body.session_id)
    aid = "a-" + str(uuid4())
    record = {"analysis_id": aid, "session_id": body.session_id, "status": "running", "data_origin": session["events"][0]["data_origin"], "trace": [], "created_at": datetime.now(timezone.utc).isoformat()}
    store.put(uid, "analyses", aid, record, expected_generation=generation)
    try:
        if os.environ.get("AGENT_BACKEND") == "engine":
            from server.remote_engine import RemoteEngineRunner
            runner = RemoteEngineRunner(store, authorization)
        elif os.environ.get("AGENT_BACKEND") == "adk":
            from server.adk_agent import AdkBehaviorRunner
            runner = AdkBehaviorRunner(store)
        else:
            from server.agent import AgentRunner
            runner = AgentRunner(store)
        result, trace = runner.run(uid, body.session_id, aid)
        record.update(status="insufficient_data" if result.goal_alignment == "unknown" and not result.segments else "completed", result=result.model_dump(mode="json"), trace=trace)
    except Exception:
        record.update(status="failed", error="analysis unavailable; retry later")
        record["trace"] = getattr(locals().get("runner"), "trace", [])
    store.put(uid, "analyses", aid, record, expected_generation=generation)
    return record

@app.get("/v1/analyses/{analysis_id}")
def result(analysis_id: str, uid=Depends(authenticated_user), store=Depends(get_store)):
    return owned(store, uid, "analyses", analysis_id)

@app.post("/v1/analyses/{analysis_id}/feedback")
def feedback(analysis_id: str, body: FeedbackInput, uid=Depends(authenticated_user), store=Depends(get_store)):
    generation = store.generation(uid)
    analysis = owned(store, uid, "analyses", analysis_id)
    if analysis["status"] not in {"completed", "insufficient_data"}: raise HTTPException(409, "analysis is not ready")
    fid = "f-" + str(uuid4())
    value = body.model_dump() | {"feedback_id": fid, "analysis_id": analysis_id, "session_id": analysis["session_id"], "created_at": datetime.now(timezone.utc).isoformat()}
    store.put(uid, "feedback", fid, value, immutable=True, expected_generation=generation)
    return value

@app.get("/v1/analyses/{analysis_id}/feedback")
def read_feedback(analysis_id: str, uid=Depends(authenticated_user), store=Depends(get_store)):
    owned(store, uid, "analyses", analysis_id)
    return [f for f in store.list(uid, "feedback") if f["analysis_id"] == analysis_id]

@app.delete("/v1/user/data")
def delete_data(uid=Depends(authenticated_user), store=Depends(get_store)):
    store.delete_all(uid)
    return {"deleted": True}
