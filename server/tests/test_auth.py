import os
import unittest
from unittest.mock import patch

import firebase_admin
from fastapi import HTTPException
from google.auth.exceptions import RefreshError, TransportError
from server.auth import authenticated_user


class AuthenticationFailureTest(unittest.TestCase):
    def test_backend_credentials_failure_is_not_user_unauthorized(self):
        with patch.dict(os.environ, {'GOOGLE_CLOUD_PROJECT': 'dopadopa'}), \
                patch.dict(firebase_admin._apps, {'test': object()}):
            for failure in (RefreshError('private credential detail'), TransportError('private transport detail')):
                with self.subTest(failure=type(failure).__name__), \
                        patch('firebase_admin.auth.verify_id_token', side_effect=failure):
                    with self.assertRaises(HTTPException) as caught:
                        authenticated_user('Bearer synthetic-token')
                    self.assertEqual(503, caught.exception.status_code)
                    self.assertNotIn('private', caught.exception.detail)

    def test_invalid_token_remains_unauthorized(self):
        with patch.dict(os.environ, {'GOOGLE_CLOUD_PROJECT': 'dopadopa'}), \
                patch.dict(firebase_admin._apps, {'test': object()}), \
                patch('firebase_admin.auth.verify_id_token', side_effect=ValueError('bad token')):
            with self.assertRaises(HTTPException) as caught:
                authenticated_user('Bearer synthetic-token')
            self.assertEqual(401, caught.exception.status_code)

    def test_missing_project_reports_server_configuration_failure(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(HTTPException) as caught:
                authenticated_user('Bearer synthetic-token')
            self.assertEqual(503, caught.exception.status_code)
