import unittest
from unittest.mock import patch
from tools.cloud_cli import gcloud_executable


class CloudCliTest(unittest.TestCase):
    def test_windows_sdk_from_path(self):
        with patch("tools.cloud_cli.shutil.which", return_value="D:/SDK/gcloud.cmd"):
            self.assertEqual("D:/SDK/gcloud.cmd", gcloud_executable())

    def test_unix_sdk_fallback(self):
        with patch("tools.cloud_cli.shutil.which", side_effect=[None, "/opt/sdk/gcloud"]):
            self.assertEqual("/opt/sdk/gcloud", gcloud_executable())

    def test_missing_sdk_stops_before_authentication(self):
        with patch("tools.cloud_cli.shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "add gcloud to PATH"):
                gcloud_executable()
