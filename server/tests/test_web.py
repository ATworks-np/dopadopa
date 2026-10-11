import unittest
from fastapi.testclient import TestClient
from server.app import app

class WebBoundaryTest(unittest.TestCase):
    def test_public_shell_and_assets_keep_private_api_protected(self):
        client = TestClient(app)
        page = client.get('/')
        self.assertEqual(200, page.status_code)
        self.assertIn('sample', client.get('/assets/app.js').text)
        self.assertIn('frame-ancestors \'none\'', page.headers['Content-Security-Policy'])
        self.assertEqual('no-store', page.headers['Cache-Control'])
        self.assertEqual('no-referrer', page.headers['Referrer-Policy'])
        self.assertEqual(401, client.get('/v1/sessions').status_code)
        self.assertEqual(401, client.post('/v1/analyses', json={'session_id':'sample-learning-only'}).status_code)
        self.assertEqual(404, client.get('/assets/../auth.py').status_code)
        self.assertEqual(404, client.get('/assets/.env').status_code)

    def test_public_samples_have_explicit_provenance_and_missing_text(self):
        rows = TestClient(app).get('/v1/demo/sessions').json()
        for row in rows:
            self.assertEqual('sample', row['data_origin'])
            for event in row['session']['events']:
                self.assertEqual('sample', event['data_origin'])
                if event['text_status'] != 'available':
                    self.assertIsNone(event['text'])
        unobservable = next(r for r in rows if r['scenario']=='unobservable')
        self.assertTrue(all(e['text'] is None for e in unobservable['session']['events']))
