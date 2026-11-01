from copy import deepcopy

import pytest


@pytest.mark.parametrize("path", ["/investigate", "/simulate"])
def test_service_key_is_required(client, small_snapshot, path):
    response = client.post(path, json=small_snapshot)
    assert response.status_code == 401
    assert response.json()["code"] == "SERVICE_ERROR"


def test_wrong_key_rejected(client, small_snapshot):
    assert client.post("/investigate", json=small_snapshot, headers={"X-Service-Key": "wrong"}).status_code == 401


def test_http_investigate_then_simulate(client, headers, snapshot):
    investigation = client.post("/investigate", headers=headers, json=snapshot)
    assert investigation.status_code == 200
    body = investigation.json()
    assert body["method"] == "DETERMINISTIC_FALLBACK"
    simulation = client.post("/simulate", headers=headers, json={**snapshot, "candidate_actions": body["candidate_actions"]})
    assert simulation.status_code == 200
    results = simulation.json()["results"]
    assert [r["decision"] for r in results] == ["REJECTED", "ELIGIBLE"]
    assert results[0]["duplicate_attempt_risk"] == 800
    assert results[1]["affected_records"] == 200
    assert results[1]["source_hash"] == snapshot["source_hash"]


@pytest.mark.parametrize("actions", [["DROP_TABLE"], ["RESUME_PENDING_ONLY"] * 2, []])
def test_bad_action_lists_are_request_validation_errors(client, headers, small_snapshot, actions):
    result = client.post("/simulate", headers=headers, json={**small_snapshot, "candidate_actions": actions})
    assert result.status_code == 422
    assert result.json()["code"] == "VALIDATION_ERROR"


@pytest.mark.parametrize("change", [
    {"processing_count": "1"},
    {"processing_count": True},
    {"reconciliation_matches": "true"},
    {"amount": "12.345"},
    {"amount": "NaN"},
    {"amount": "Infinity"},
    {"amount": 0},
    {"amount": -12.10},
    {"amount": True},
    {"currency": "sgd"},
    {"status": "UNTRUSTED"},
    {"execute_sql": "select secret"},
])
def test_malformed_records_rejected_without_echoing_body(client, headers, small_snapshot, change):
    small_snapshot["records"][0].update(change)
    result = client.post("/investigate", headers=headers, json=small_snapshot)
    assert result.status_code == 422
    assert "records.0" in result.json()["message"]
    assert "select secret" not in result.text


def test_reconciliation_mismatch_http_handoff(client, headers, small_snapshot):
    small_snapshot["exception_type"] = "RECONCILIATION_MISMATCH"
    small_snapshot["records"][0]["reconciliation_matches"] = False
    investigation = client.post("/investigate", headers=headers, json=small_snapshot).json()
    assert investigation["candidate_actions"] == ["REQUEST_MANUAL_REVIEW"]
    result = client.post("/simulate", headers=headers, json={**small_snapshot, "candidate_actions": investigation["candidate_actions"]})
    assert result.status_code == 200
    assert result.json()["results"][0]["decision"] == "REQUIRES_APPROVAL"


def test_duplicate_keys_return_rejected_simulation_not_success(client, headers, small_snapshot):
    small_snapshot["records"][-1]["idempotency_key"] = small_snapshot["records"][0]["idempotency_key"]
    result = client.post("/simulate", headers=headers, json={**small_snapshot, "candidate_actions": ["RESUME_PENDING_ONLY"]})
    assert result.status_code == 200
    assert result.json()["results"][0]["decision"] == "REJECTED"


def test_openapi_documents_strict_schemas(client):
    spec = client.get("/openapi.json").json()
    assert "/investigate" in spec["paths"] and "/simulate" in spec["paths"]
    assert spec["components"]["schemas"]["InvestigationRequest"]["additionalProperties"] is False
    assert "X-Service-Key" in str(spec["paths"]["/simulate"])
