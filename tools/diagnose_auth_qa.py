"""Diagnose synthetic signup without printing credentials or provider bodies."""
import json
import secrets
import requests
from pathlib import Path

def main():
    key=json.loads(Path('artifacts/cloud/firebase-web-config.json').read_text(encoding='utf-8'))['apiKey']
    base='https://identitytoolkit.googleapis.com/v1/accounts:'
    response=requests.post(base+'signUp',params={'key':key},json={
        'email':'webqa-'+secrets.token_hex(12)+'@example.invalid',
        'password':secrets.token_urlsafe(32),'returnSecureToken':True},timeout=30)
    if response.ok:
        token=response.json()['idToken']
        deleted=requests.post(base+'delete',params={'key':key},json={'idToken':token},timeout=30)
        deleted.raise_for_status()
        result={'signup':'passed','temporary_account_deleted':True}
    else:
        code=response.json().get('error',{}).get('message','').split(' : ')[0]
        allowed={'TOO_MANY_ATTEMPTS_TRY_LATER','INVALID_EMAIL','OPERATION_NOT_ALLOWED','QUOTA_EXCEEDED','ADMIN_ONLY_OPERATION','API_KEY_INVALID','PASSWORD_DOES_NOT_MEET_REQUIREMENTS'}
        result={'signup':'failed','http_status':response.status_code,'code':code if code in allowed else 'unclassified'}
    Path('artifacts/cloud/auth-qa-diagnostic.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':main()
