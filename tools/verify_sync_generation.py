"""Actual API deletion-generation checks, isolated from model availability."""
import json
import secrets
from pathlib import Path
import requests
from server.samples import sample_batch

def main():
    key=json.loads(Path('artifacts/cloud/firebase-web-config.json').read_text(encoding='utf-8'))['apiKey']
    base='https://dopaguard-api-463106527119.asia-northeast1.run.app'
    token=None
    result={'status':'failed','data_origin':'sample','checks':[]}
    def identity(action,body):
        response=requests.post('https://identitytoolkit.googleapis.com/v1/accounts:'+action,params={'key':key},json=body,timeout=30)
        response.raise_for_status()
        return response.json()
    def api(method,path,body=None,generation=None,expected=200):
        headers={'Authorization':'Bearer '+token}
        if generation is not None: headers['X-DopaGuard-Generation']=str(generation)
        response=requests.request(method,base+path,headers=headers,json=body,timeout=30)
        if response.status_code!=expected: raise RuntimeError('API status mismatch')
        return response.json()
    try:
        token=identity('signUp',{'email':'generation-'+secrets.token_hex(12)+'@example.invalid',
            'password':secrets.token_urlsafe(32),'returnSecureToken':True})['idToken']
        old=api('GET','/v1/user/generation')['generation']
        batch=sample_batch('learning-only').model_dump(mode='json')
        api('POST','/v1/sessions:batch',batch,old)
        api('DELETE','/v1/user/data')
        api('POST','/v1/sessions:batch',batch,old,409)
        api('POST','/v1/sessions:batch',batch,expected=409)
        api('POST','/v1/demo/sessions/learning-only/import',generation=old,expected=409)
        assert api('GET','/v1/sessions')==[]
        result['checks'].append('old and omitted generation cannot restore deleted records on first arrival')
        current=api('GET','/v1/user/generation')['generation']
        assert current>old
        api('POST','/v1/sessions:batch',batch,current)
        api('POST','/v1/sessions:batch',batch,current)
        assert len(api('GET','/v1/sessions'))==1
        result['checks'].append('explicit new generation registration and immutable replay accepted')
        result['status']='passed'
    except Exception as error:
        result['error_type']=type(error).__name__
    finally:
        if token:
            api('DELETE','/v1/user/data')
            assert api('GET','/v1/sessions')==[]
            identity('delete',{'idToken':token})
            result['temporary_account_cleaned']=True
        Path('artifacts/cloud/sync-generation-smoke.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result))
    if result['status']!='passed': raise RuntimeError('generation verification failed')

if __name__=='__main__':main()
