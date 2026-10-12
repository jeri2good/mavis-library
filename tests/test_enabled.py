import pytest

from service import studio_enabled


@pytest.mark.parametrize('secret,override,enabled', [('1', '', True), ('0', '', False), ('1', '0', False), ('0', '1', True)])
def test_studio_enabled_secret_and_deployment_override(monkeypatch, secret, override, enabled):
    monkeypatch.setenv('STUDIO_ENABLED', secret)
    monkeypatch.setenv('STUDIO_DEPLOY_ENABLED', override)
    assert studio_enabled() is enabled
