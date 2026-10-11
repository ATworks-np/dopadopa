"""Custom Agent Engine operation; Firebase identity is verified inside runtime.

No model-supplied owner or write tools. Runtime is stateless between calls.
Deployment must keep payload/token tracing disabled.
"""
import os
import re
from concurrent.futures import ThreadPoolExecutor
from server.auth import authenticated_user
from server.store import FirestoreStore, ConflictError
from server.adk_agent import AdkBehaviorRunner, InvestigationFailure

class DopaGuardEngineAgent:
    def query(self, firebase_token: str, session_id: str, analysis_id: str,
              expected_generation: int) -> dict:
        # Managed runtime prints failing call arguments to stderr. Never let a
        # business/auth/model exception escape with a Firebase token in kwargs.
        try:
            return self._investigate(firebase_token, session_id, analysis_id, expected_generation)
        except ConflictError:
            return {"error": "generation_conflict"}
        except KeyError:
            return {"error": "record_unavailable"}
        except InvestigationFailure as error:
            return {"error": "analysis_unavailable", "stage": error.stage}
        except Exception:
            return {"error": "analysis_unavailable"}

    def _investigate(self, firebase_token: str, session_id: str, analysis_id: str,
                     expected_generation: int) -> dict:
        if os.environ.get("DOPAGUARD_PROJECT"):
            os.environ["GOOGLE_CLOUD_PROJECT"] = os.environ["DOPAGUARD_PROJECT"]
            os.environ["GOOGLE_CLOUD_LOCATION"] = os.environ.get("DOPAGUARD_MODEL_LOCATION", "global")
        if not isinstance(firebase_token, str) or not 1 <= len(firebase_token) <= 16_000:
            raise ValueError("invalid authentication")
        for value in (session_id, analysis_id):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
                raise ValueError("invalid ID")
        # Protobuf Struct serializes JSON numbers as doubles at the RPC boundary.
        if (type(expected_generation) not in (int, float) or not 0 <= expected_generation <= 2**53 - 1
                or expected_generation != int(expected_generation)):
            raise ValueError("invalid generation")
        expected_generation = int(expected_generation)
        uid = authenticated_user("Bearer " + firebase_token)
        store = FirestoreStore(os.environ["GOOGLE_CLOUD_PROJECT"])
        try:
            if store.generation(uid) != expected_generation:
                raise ConflictError("data changed before investigation")
            # Managed query handlers may already run on an asyncio event loop.
            # Give the synchronous runner its own loop without retaining state.
            runner = AdkBehaviorRunner(store)
            with ThreadPoolExecutor(max_workers=1) as worker:
                try:
                    result, trace = worker.submit(runner.run, uid, session_id, analysis_id).result(timeout=50)
                except Exception:
                    stage = getattr(runner, "failure_stage", None)
                    if isinstance(stage, str) and stage in {"model_execution", "model_deadline", "result_validation"}:
                        raise InvestigationFailure(stage) from None
                    raise
            if store.generation(uid) != expected_generation:
                raise ConflictError("data changed during investigation")
            return {"result": result.model_dump(mode="json"), "trace": trace}
        finally:
            store.db.close()
