from copy import deepcopy

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import app, get_settings


def make_snapshot(total: int = 1000, processed: int = 800) -> dict:
    return {
        "exception_id": "EX-PARTIAL-001",
        "exception_type": "PARTIAL_BATCH_FAILURE",
        "batch_id": "BATCH-SYNTHETIC-001",
        "source_hash": "a" * 64,
        "failure_reason": "Synthetic worker timeout after partial commit",
        "evidence": ["Batch status is FAILED_PARTIAL"],
        "previous_incidents": [],
        "records": [
            {
                "id": f"TX-{i}", "status": "PROCESSED" if i < processed else "PENDING",
                "idempotency_key": f"PROCESS-{i}", "processing_count": 1 if i < processed else 0,
                "amount": "125.10", "currency": "SGD", "source_reference": f"SRC-{i}",
                "reconciliation_count": 1 if i < processed else 0,
                "reconciliation_matches": i < processed,
            }
            for i in range(total)
        ],
    }


@pytest.fixture
def snapshot():
    return make_snapshot()


@pytest.fixture
def small_snapshot():
    return make_snapshot(13, 7)


@pytest.fixture
def settings():
    return Settings(service_key="test-service-key", gemini_api_key="", gemini_model="configured-test-model")


@pytest.fixture
def client(settings):
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as client:
        yield client
    app.dependency_overrides.clear()


@pytest.fixture
def headers():
    return {"X-Service-Key": "test-service-key"}


@pytest.fixture
def simulation_request(snapshot):
    return {**deepcopy(snapshot), "candidate_actions": ["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"]}
