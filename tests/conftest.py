import pytest


@pytest.fixture(autouse=True)
def isolated_studio_secret(monkeypatch):
    monkeypatch.setenv("STUDIO_SHARED_SECRET", "test-shared-secret-not-for-production-123456789")
