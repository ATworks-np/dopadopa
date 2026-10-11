"""Locate the installed Cloud SDK without copying another machine's auth state."""
import shutil


def gcloud_executable():
    executable = shutil.which("gcloud.cmd") or shutil.which("gcloud")
    if not executable:
        raise RuntimeError("Install Google Cloud SDK and add gcloud to PATH")
    return executable
