import os
from fastapi import Header, HTTPException
from google.auth.exceptions import GoogleAuthError

def authenticated_user(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "authentication required")
    token = authorization[7:]
    if not os.environ.get("GOOGLE_CLOUD_PROJECT"):
        raise HTTPException(503, "server authentication unavailable")
    try:
        import firebase_admin
        from firebase_admin import auth
        if not firebase_admin._apps:
            firebase_admin.initialize_app(options={"projectId": os.environ["GOOGLE_CLOUD_PROJECT"]})
        claims = auth.verify_id_token(token, check_revoked=True)
        return claims["uid"]
    except GoogleAuthError:
        # Backend credentials/transport failures are not invalid user tokens.
        raise HTTPException(503, "server authentication unavailable") from None
    except auth.CertificateFetchError:
        raise HTTPException(503, "server authentication unavailable") from None
    except Exception:
        # Tokens and provider errors must not be returned or logged.
        raise HTTPException(401, "invalid authentication") from None
