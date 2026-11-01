from copy import deepcopy
from datetime import datetime

import pytest
from pydantic import ValidationError

from app.schemas import SimulationRequest
from app.simulation.engine import simulate, simulate_action
from conftest import make_snapshot


def twin(snapshot, actions=None):
    return simulate(SimulationRequest.model_validate({
        **snapshot, "candidate_actions": actions or ["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"],
    })).results


def test_thousand_record_acceptance(snapshot):
    full, selective = twin(snapshot)
    assert (full.total_records, full.already_processed, full.pending_records) == (1000, 800, 200)
    assert (full.affected_records, full.expected_successful_records, full.duplicate_attempt_risk) == (1000, 1000, 800)
    assert not full.safe and full.decision == "REJECTED"
    assert any("reprocessed" in violation for violation in full.policy_violations)
    assert (selective.affected_records, selective.expected_successful_records, selective.duplicate_attempt_risk) == (200, 1000, 0)
    assert selective.safe and selective.decision == "ELIGIBLE"
    assert selective.policy_violations == []


@pytest.mark.parametrize("total,processed", [(2, 1), (37, 9), (413, 17), (1501, 999)])
def test_counts_derive_from_snapshot(total, processed):
    full, selective = twin(make_snapshot(total, processed))
    assert full.duplicate_attempt_risk == processed
    assert full.affected_records == total
    assert selective.affected_records == total - processed
    assert selective.expected_successful_records == total
    assert selective.already_processed == processed


def test_deep_copy_keeps_every_original_field_unchanged(snapshot):
    original_json = deepcopy(snapshot)
    request = SimulationRequest.model_validate({**snapshot, "candidate_actions": ["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"]})
    before = request.model_dump()
    results = simulate(request).results
    assert request.model_dump() == before
    assert snapshot == original_json
    # Running the unsafe strategy first must not contaminate the second strategy.
    assert results[1].safe
    assert results[1].duplicate_attempt_risk == 0


def test_reversing_strategies_does_not_change_their_effects(small_snapshot):
    forward = twin(small_snapshot)
    reverse = twin(small_snapshot, ["RESUME_PENDING_ONLY", "RETRY_FULL_BATCH"])
    for a, b in zip(forward, reversed(reverse)):
        assert a.model_dump(exclude={"simulation_id", "created_at"}) == b.model_dump(exclude={"simulation_id", "created_at"})


@pytest.mark.parametrize("field", ["id", "idempotency_key", "source_reference"])
def test_duplicate_identifiers_block_selective_recovery(small_snapshot, field):
    small_snapshot["records"][-1][field] = small_snapshot["records"][0][field]
    full, selective = twin(small_snapshot)
    assert not selective.safe and selective.decision == "REJECTED"
    assert any("duplicate" in item for item in selective.policy_violations)


@pytest.mark.parametrize("index,change", [
    (0, {"processing_count": 0}),
    (0, {"processing_count": 2}),
    (-1, {"processing_count": 1}),
    (0, {"reconciliation_count": 0}),
    (0, {"reconciliation_count": 2}),
    (0, {"reconciliation_matches": False}),
    (-1, {"reconciliation_count": 1}),
    (-1, {"status": "FAILED"}),
])
def test_inconsistent_processing_or_reconciliation_never_becomes_eligible(small_snapshot, index, change):
    small_snapshot["records"][index].update(change)
    selective = twin(small_snapshot)[1]
    assert not selective.safe
    assert selective.decision == "REJECTED"
    assert selective.policy_violations


@pytest.mark.parametrize("total,processed", [(0, 0), (4, 4)])
def test_empty_or_completed_snapshots_do_not_authorize_recovery(total, processed):
    assert all(not result.safe for result in twin(make_snapshot(total, processed)))


def test_full_retry_with_no_processed_records_has_no_duplicate_risk():
    full, selective = twin(make_snapshot(6, 0))
    assert full.duplicate_attempt_risk == 0
    # The twin reports effects: backend policy separately confirms the case type and lifecycle.
    assert full.safe and selective.safe


@pytest.mark.parametrize("exception_type", ["DUPLICATE_TRANSACTION_RECORD", "RECONCILIATION_MISMATCH"])
def test_nonpartial_case_cannot_use_automated_recovery(small_snapshot, exception_type):
    small_snapshot["exception_type"] = exception_type
    assert all(not result.safe for result in twin(small_snapshot))


def test_manual_review_only_predicts_handoff(small_snapshot):
    small_snapshot["exception_type"] = "RECONCILIATION_MISMATCH"
    small_snapshot["records"][0]["reconciliation_matches"] = False
    manual = twin(small_snapshot, ["REQUEST_MANUAL_REVIEW"])[0]
    assert manual.decision == "REQUIRES_APPROVAL" and not manual.safe
    assert manual.affected_records == 0
    assert manual.expected_successful_records == 7
    assert manual.duplicate_attempt_risk == 0


def test_source_hash_and_timestamp_bound_the_result(small_snapshot):
    small_snapshot["source_hash"] = "current-version-17"
    result = twin(small_snapshot)[1]
    assert result.source_hash == "current-version-17"
    assert result.created_at.tzinfo is not None
    assert result.simulation_id.startswith("SIM-")
    small_snapshot["source_hash"] = "current-version-18"
    small_snapshot["records"][-1].update(status="PROCESSED", processing_count=1,
                                        reconciliation_count=1, reconciliation_matches=True)
    newer = twin(small_snapshot)[1]
    assert newer.source_hash != result.source_hash
    assert newer.simulation_id != result.simulation_id
    assert newer.affected_records == result.affected_records - 1


def test_unknown_action_rejected_even_outside_http(small_snapshot):
    with pytest.raises(ValidationError):
        SimulationRequest.model_validate({**small_snapshot, "candidate_actions": ["RUN_SQL"]})
    request = SimulationRequest.model_validate({**small_snapshot, "candidate_actions": ["RESUME_PENDING_ONLY"]})
    with pytest.raises(ValueError, match="Unknown"):
        simulate_action(request, "RUN_SQL")
