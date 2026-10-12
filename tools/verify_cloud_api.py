"""Real Firebase/Cloud Run/Firestore/Vertex smoke test with temporary accounts."""
import json
import argparse
import secrets
import time
from datetime import datetime, timezone
from pathlib import Path
import requests
from server.models import Analysis
from server.samples import sample_batch

BASE = "https://dopaguard-api-463106527119.asia-northeast1.run.app"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine-qa", action="store_true")
    parser.add_argument("--scenario", choices=("learning-only", "learning-shorts", "unobservable"), default="learning-only")
    args = parser.parse_args()
    base = "https://engine-qa---dopaguard-api-p4kp2phc5q-an.a.run.app" if args.engine_qa else BASE
    import_path = "/v1/demo/sessions/" + args.scenario + "/import"
    key = json.loads(Path("artifacts/cloud/firebase-web-config.json").read_text(encoding="utf-8"))["apiKey"]
    tokens, checks, timings = [], [], []
    def identity(method, body):
        response = requests.post("https://identitytoolkit.googleapis.com/v1/accounts:" + method,
            params={"key": key}, json=body, timeout=30)
        if not response.ok: raise RuntimeError(f"identity {method}: HTTP {response.status_code}")
        return response.json()
    def api(method, path, token=None, body=None, expected=200, generation=None):
        headers = {"Authorization": "Bearer " + token} if token else {}
        if generation is not None: headers['X-DopaGuard-Generation'] = str(generation)
        # Store route shape and duration only, never IDs, tokens or bodies.
        route = path
        if path.startswith("/v1/sessions/"): route = "/v1/sessions/{id}"
        if path.startswith("/v1/analyses/"):
            route = "/v1/analyses/{id}" + ("/feedback" if path.endswith("/feedback") else "")
        started = time.monotonic()
        timing = {"method": method, "route": route}
        try:
            wait = 130 if path == "/v1/analyses" or path.endswith("/analyze") else 65
            response = requests.request(method, base + path,
                headers=headers, json=body, timeout=wait)
            timing["http_status"] = response.status_code
        except requests.RequestException as error:
            timing["error_type"] = type(error).__name__
            raise
        finally:
            timing["elapsed_ms"] = round((time.monotonic() - started) * 1000)
            timings.append(timing)
        if response.status_code != expected: raise RuntimeError(f"API {path}: HTTP {response.status_code}, expected {expected}")
        return response.json()
    result = {"project": "dopadopa", "data_origin": "sample", "scenario": args.scenario, "checks": checks,
        "started_at": datetime.now(timezone.utc).isoformat(), "request_timings": timings}
    try:
        for _ in range(2):
            account = identity("signUp", {"email": "smoke-" + secrets.token_hex(12) + "@example.invalid",
                "password": secrets.token_urlsafe(32), "returnSecureToken": True})
            tokens.append(account["idToken"])
        checks.append("Firebase email/password signup")
        api("GET", "/health")
        api("GET", "/v1/sessions", expected=401)
        api("GET", "/v1/sessions", "invalid-token", expected=401)
        checks.append("health and unauthenticated/invalid token rejection")
        old_generation = api('GET','/v1/user/generation',tokens[0])['generation']
        imported = api("POST", import_path, tokens[0], generation=old_generation)
        assert imported["data_origin"] == "sample"
        sid = imported["session_id"]
        api("GET", "/v1/sessions/" + sid, tokens[1], expected=404)
        checks.append("real Firebase token owner isolation and sample provenance")
        analysis = api("POST", "/v1/analyses", tokens[0], {"session_id": sid})
        result["analysis_status"] = analysis["status"]
        result["trace"] = analysis["trace"]
        if analysis["status"] not in {"completed", "insufficient_data"}:
            raise RuntimeError("real cloud analysis failed")
        assert analysis["result"]["data_origin"] == "sample"
        assert analysis["result"]["user_confirmation_required"] is True
        validated = Analysis.model_validate(analysis["result"])
        assert validated.analysis_id == analysis["analysis_id"] and validated.session_id == sid
        sample = sample_batch(args.scenario)
        validated.validate_evidence(sample.events)
        required_tools = {"get_user_goals", "get_timeline", "get_click_events"}
        assert required_tools <= {row["tool"] for row in analysis["trace"]}
        result["evidence_valid"] = True
        checks.append("analysis identity, sample evidence and mandatory investigation tools verified")
        if args.scenario == "unobservable":
            assert analysis["status"] == "insufficient_data"
            assert analysis["result"]["goal_alignment"] == "unknown"
            assert not analysis["result"]["segments"] and not analysis["result"]["transitions"]
            checks.append("unobservable sample stays unknown without invented segments or transitions")
        aid = analysis["analysis_id"]
        api("GET", "/v1/analyses/" + aid, tokens[1], expected=404)
        checks.append("Cloud Run analysis and other owner rejection")
        saved = api("POST", "/v1/analyses/" + aid + "/feedback", tokens[0],
            {"verdict": "incorrect", "corrected_intent": "架空サンプルの訂正検証"})
        rows = api("GET", "/v1/analyses/" + aid + "/feedback", tokens[0])
        assert any(row["feedback_id"] == saved["feedback_id"] for row in rows)
        checks.append("explicit correction saved and read back")
        api("DELETE", "/v1/user/data", tokens[0])
        assert api("GET", "/v1/sessions", tokens[0]) == []
        api("GET", "/v1/analyses/" + aid, tokens[0], expected=404)
        checks.append("owner data deletion verified")
        api('POST',import_path,tokens[0],expected=409,generation=old_generation)
        api('POST',import_path,tokens[0],expected=409)
        assert api('GET','/v1/sessions',tokens[0]) == []
        current = api('GET','/v1/user/generation',tokens[0])['generation']
        assert current > old_generation
        api('POST',import_path,tokens[0],generation=current)
        assert len(api('GET','/v1/sessions',tokens[0])) == 1
        checks.append('old and missing generation rejected after deletion; explicit current generation sync accepted')
        result["status"] = "passed"
    except Exception as error:
        result.update(status="failed", error_type=type(error).__name__)
        print("Cloud smoke failed: " + type(error).__name__, flush=True)
    finally:
        cleanup = []
        for token in tokens:
            try:
                api("DELETE", "/v1/user/data", token)
                identity("delete", {"idToken": token})
                cleanup.append(True)
            except Exception: cleanup.append(False)
        result["temporary_accounts_cleaned"] = cleanup
        result["target"] = base
        filename = "engine-tag-api-smoke.json" if args.engine_qa else "cloud-api-smoke.json"
        if args.scenario != "learning-only":
            filename = filename.removesuffix(".json") + "-" + args.scenario + ".json"
        Path("artifacts/cloud/" + filename).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))
    if result["status"] != "passed" or not all(result["temporary_accounts_cleaned"]):
        raise RuntimeError("cloud API verification failed")

if __name__ == "__main__": main()
