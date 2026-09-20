from training_plan_collector.settings import CollectorSettings


def test_default_settings(monkeypatch):
    monkeypatch.delenv("COLLECTOR_REDIS_HOST", raising=False)
    settings = CollectorSettings(_env_file=None)

    assert settings.redis_host == "127.0.0.1"
    assert settings.redis_port == 6379
    assert settings.redis_database == 5
    assert settings.task_queue == "training-plan:sync:jobs"
