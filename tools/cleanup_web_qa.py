"""Remove only the named disposable browser QA fixture; no saved credentials."""
import json
import subprocess
import sys
import requests
from google.oauth2.credentials import Credentials
from google.cloud import firestore
from server.store import FirestoreStore

def main():
    email = sys.argv[1]
    if not (email.startswith('webqa-') and email.endswith('@example.invalid')):
        raise ValueError('only disposable web QA fixtures allowed')
    token = subprocess.run([r'C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd',
        'auth', 'print-access-token', '--account=atomic1017@gmail.com'], capture_output=True, text=True, check=True).stdout.strip()
    session = requests.Session()
    session.headers.update({'Authorization':'Bearer '+token,'x-goog-user-project':'dopadopa'})
    base = 'https://identitytoolkit.googleapis.com/v1/projects/dopadopa/accounts:'
    response = session.post(base+'lookup', json={'email':[email]}, timeout=30)
    response.raise_for_status()
    users = response.json().get('users', [])
    if not users:
        print(json.dumps({'disposable_account_absent':True,'matched_accounts':0}))
        return
    if len(users) != 1 or users[0]['email'] != email: raise ValueError('fixture identity mismatch')
    uid = users[0]['localId']
    store = FirestoreStore.__new__(FirestoreStore)
    store.db = firestore.Client(project='dopadopa', credentials=Credentials(token=token))
    store.delete_all(uid)
    assert all(not store.list(uid, kind) for kind in ('sessions','analyses','feedback'))
    response = session.post(base+'delete', json={'localId':uid}, timeout=30)
    response.raise_for_status()
    verified = session.post(base+'lookup', json={'email':[email]}, timeout=30)
    verified.raise_for_status()
    assert not verified.json().get('users')
    print(json.dumps({'disposable_account_deleted':True,'content_deleted':True}))

if __name__=='__main__': main()
