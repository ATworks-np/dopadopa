"""Revalidate saved real-provider sample responses; does not call Gemini."""
import hashlib
import json
from pathlib import Path
from server.models import Analysis
from server.samples import SCENARIOS, sample_batch

def main():
    source = Path('artifacts/cloud/gemini-smoke.json')
    raw = source.read_bytes()
    saved = json.loads(raw)
    assert saved['data_origin'] == 'sample'
    assert {r['scenario'] for r in saved['records']} == set(SCENARIOS)
    assert len(saved['records']) == len(SCENARIOS)
    results = []
    for row in saved['records']:
        assert row['status'] == 'completed'
        batch = sample_batch(row['scenario'])
        value = Analysis.model_validate(row['analysis']).validate_evidence(batch.events)
        assert value.session_id == batch.session_id
        assert value.analysis_id == 'verify-'+row['scenario']
        assert value.data_origin == 'sample'
        assert {'get_user_goals','get_timeline','get_click_events'} <= {t['tool'] for t in row['trace']}
        if row['scenario'] == 'unobservable':
            assert value.goal_alignment == 'unknown' and not value.segments and not value.transitions
        if row['scenario'] == 'learning-only':
            assert not value.transitions, 'continued learning must not invent a transition from elapsed time'
        results.append({'scenario':row['scenario'],'schema_and_evidence_valid':True,
            'goal_alignment':value.goal_alignment,'segments':len(value.segments),
            'transitions':len(value.transitions),'user_confirmation_required':value.user_confirmation_required})
    report = {'status':'passed','verification':'saved provider responses revalidated, no new inference',
        'source_sha256':hashlib.sha256(raw).hexdigest(),
        'validator_sha256':hashlib.sha256(Path('server/models.py').read_bytes()).hexdigest(),
        'data_origin':'sample','results':results,
        'limitations':['Real YouTube collection not tested','Schema validity does not establish correctness of inferred intent']}
    Path('artifacts/cloud/analysis-evidence-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    try: main()
    except Exception as error:
        Path('artifacts/cloud/analysis-evidence-audit.json').write_text(json.dumps({
            'status':'failed','data_origin':'sample','error_type':type(error).__name__,
            'reason':'saved response does not satisfy scenario acceptance',
            'source_sha256':hashlib.sha256(Path('artifacts/cloud/gemini-smoke.json').read_bytes()).hexdigest()
        },indent=2),encoding='utf-8')
        raise
