"""Authenticated live snapshot smoke test using temporary sample accounts only."""
import json
import secrets
from pathlib import Path
import requests
from server.models import Analysis
from server.tests.test_live import batch

BASE = 'https://engine-qa---dopaguard-api-p4kp2phc5q-an.a.run.app'

def main():
    key = json.loads(Path('artifacts/cloud/firebase-web-config.json').read_text())['apiKey']
    tokens, checks = [], []
    result = {'data_origin': 'sample', 'fixture_source': 'synthetic', 'checks': checks, 'target': BASE}
    def identity(method, body):
        response = requests.post('https://identitytoolkit.googleapis.com/v1/accounts:' + method,
            params={'key': key}, json=body, timeout=30)
        response.raise_for_status()
        return response.json()
    def api(method, path, token, body=None, expected=200, generation=None):
        headers = {'Authorization': 'Bearer ' + token}
        if generation is not None: headers['X-DopaGuard-Generation'] = str(generation)
        response = requests.request(method, BASE + path, headers=headers, json=body, timeout=65)
        assert response.status_code == expected, 'unexpected HTTP ' + str(response.status_code)
        return response.json()
    try:
        for _ in range(2):
            tokens.append(identity('signUp', {'email': 'live-qa-' + secrets.token_hex(12) + '@example.invalid',
                'password': secrets.token_urlsafe(32), 'returnSecureToken': True})['idToken'])
        generation = api('GET', '/v1/user/generation', tokens[0])['generation']
        body = batch('qa-' + secrets.token_hex(12)) | {'generation': generation}
        path = '/v1/live/snapshots/' + body['snapshot_id']
        first = api('POST', '/v1/live/snapshots', tokens[0], body, generation=generation)
        assert first == api('POST', '/v1/live/snapshots', tokens[0], body, generation=generation)
        api('GET', path, tokens[1], expected=404)
        api('POST', path + '/analyze', tokens[1], expected=404)
        checks.append('immutable replay and authenticated owner isolation')
        value = api('POST', path + '/analyze', tokens[0], generation=generation)
        result['analysis_status'] = value['status']
        if value['status'] == 'failed':
            # Diagnose in memory with fixed stages; never retain remote bodies or tokens.
            import subprocess
            from unittest.mock import patch
            from google.oauth2.credentials import Credentials
            from google.cloud.firestore import Client
            from tools.cloud_cli import gcloud_executable
            from server.store import FirestoreStore
            from server.remote_engine import RemoteEngineRunner
            import os
            access = subprocess.run([gcloud_executable(), 'auth', 'print-access-token'], capture_output=True, text=True, check=True).stdout.strip()
            credentials = Credentials(token=access)
            store = FirestoreStore.__new__(FirestoreStore)
            store.db = Client(project='dopadopa', credentials=credentials)
            runner = RemoteEngineRunner(store, 'Bearer ' + tokens[0])
            try:
                import base64
                payload = tokens[0].split('.')[1]
                uid = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['user_id']
                resource = json.loads(Path('artifacts/cloud/engine-deployment.json').read_text())['resource_name']
                with patch.object(credentials, 'refresh'), patch('google.auth.default', return_value=(credentials, 'dopadopa')), patch.dict(os.environ, {'AGENT_ENGINE_RESOURCE': resource}):
                    runner.run(uid, body['session_id'], value['analysis_id'], snapshot_id=body['snapshot_id'])
                result['diagnostic_stage'] = 'retry_succeeded'
            except Exception:
                result['diagnostic_stage'] = runner.failure_stage
            finally:
                store.db.close()
        assert value['status'] in ('completed', 'insufficient_data')
        assert value['snapshot_id'] == body['snapshot_id'] and value['evidence_validated']
        analysis = Analysis.model_validate(value['result'])
        assert analysis.session_id == body['session_id'] and analysis.analysis_id == value['analysis_id']
        assert analysis.data_origin == 'sample'
        from server.models import Event
        analysis.validate_evidence([Event.model_validate(row) for row in body['events']])
        assert {'get_user_goals', 'get_timeline', 'get_click_events'} <= {row['tool'] for row in value['trace']}
        result['trace'] = value['trace']
        assert value == api('POST', path + '/analyze', tokens[0], generation=generation)
        checks.append('live Agent Engine analysis, mandatory tools, bound evidence and cached replay')
        api('DELETE', '/v1/user/data', tokens[0])
        api('GET', path, tokens[0], expected=404)
        api('POST', '/v1/live/snapshots', tokens[0], body, expected=409, generation=generation)
        checks.append('deletion and stale generation rejection')
        result['status'] = 'PASS'
    except Exception as error:
        result.update(status='FAIL', error_type=type(error).__name__)
    finally:
        cleanup = []
        for token in tokens:
            try:
                api('DELETE', '/v1/user/data', token)
                identity('delete', {'idToken': token})
                cleanup.append(True)
            except Exception:
                cleanup.append(False)
        result['temporary_accounts_cleaned'] = cleanup
        Path('artifacts/cloud/live-api-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps(result, ensure_ascii=False))
    if result['status'] != 'PASS' or not all(cleanup):
        raise RuntimeError('live API verification failed')

if __name__ == '__main__': main()
