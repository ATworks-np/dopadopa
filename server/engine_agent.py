"""Custom Agent Engine operation; Firebase identity is verified inside runtime.

No model-supplied owner or write tools. Runtime is stateless between calls.
Deployment must keep payload/token tracing disabled.
"""
import os
import re
from server.auth import authenticated_user
from server.store import FirestoreStore, ConflictError
from server.adk_agent import AdkBehaviorRunner

class DopaGuardEngineAgent:
    def query(self, firebase_token: str, session_id: str, analysis_id: str,
              expected_generation: int) -> dict:
        if os.environ.get("DOPAGUARD_PROJECT"):
            os.environ["GOOGLE_CLOUD_PROJECT"] = os.environ["DOPAGUARD_PROJECT"]
            os.environ["GOOGLE_CLOUD_LOCATION"] = os.environ.get("DOPAGUARD_MODEL_LOCATION", "global")
        if not isinstance(firebase_token, str) or not 1 <= len(firebase_token) <= 16_000:
            raise ValueError("invalid authentication")
        for value in (session_id, analysis_id):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
                raise ValueError("invalid ID")
        if type(expected_generation) is not int or expected_generation < 0:
            raise ValueError("invalid generation")
        uid = authenticated_user("Bearer " + firebase_token)
        store = FirestoreStore(os.environ["GOOGLE_CLOUD_PROJECT"])
        try:
            if store.generation(uid) != expected_generation:
                raise ConflictError("data changed before investigation")
            result, trace = AdkBehaviorRunner(store).run(uid, session_id, analysis_id)
            if store.generation(uid) != expected_generation:
                raise ConflictError("data changed during investigation")
            return {"result": result.model_dump(mode="json"), "trace": trace}
        finally:
            store.db.close()
