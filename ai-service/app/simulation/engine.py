"""Simulate actual record effects on deep copies, then compare invariants.

RETRY_FULL_BATCH intentionally models an *unguarded* processing attempt against
every record. A production idempotent replay would be a different action with
different behavior. This implementation never represents full retries in general
as unsafe; the risk is measured from the supplied records and the action model.
"""

from datetime import datetime, timezone
from uuid import uuid4

from app.schemas import Action, SimulationRequest, SimulationResponse, SimulationResult
from app.simulation.invariants import inspect_records


def simulate(request: SimulationRequest) -> SimulationResponse:
    return SimulationResponse(results=[simulate_action(request, action) for action in request.candidate_actions])


def simulate_action(request: SimulationRequest, action: Action) -> SimulationResult:
    before = [record.model_copy(deep=True) for record in request.records]
    twin = [record.model_copy(deep=True) for record in before]
    facts = inspect_records(before)
    violations = list(facts.violations)
    if request.exception_type != "PARTIAL_BATCH_FAILURE":
        violations.append("Automatic recovery policy supports partial batch failure only")

    if action == "REQUEST_MANUAL_REVIEW":
        return SimulationResult(
            simulation_id=f"SIM-{uuid4()}", exception_id=request.exception_id, strategy=action,
            source_hash=request.source_hash, safe=False, total_records=facts.total,
            already_processed=facts.processed, pending_records=facts.pending, affected_records=0,
            expected_successful_records=facts.processed, duplicate_attempt_risk=0,
            policy_violations=violations, decision="REQUIRES_APPROVAL",
            explanation="Human investigation is required. This handoff changes no transaction records; reviewer acknowledgement cannot bypass safety invariants.",
            created_at=datetime.now(timezone.utc),
        )

    if action not in ("RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"):
        # Defense in depth for callers bypassing Pydantic at an internal boundary.
        raise ValueError("Unknown recovery action")

    target_indices = [i for i, r in enumerate(twin) if action == "RETRY_FULL_BATCH" or r.status == "PENDING"]
    duplicate_risk = sum(before[i].processing_count > 0 or before[i].status == "PROCESSED" for i in target_indices)
    if duplicate_risk:
        violations.append(f"Previously completed records would be reprocessed: {duplicate_risk} duplicate processing attempts")
    if not facts.pending:
        violations.append("No pending records are available for permitted recovery")

    for i in target_indices:
        record = twin[i]
        record.processing_count += 1
        record.status = "PROCESSED"
        record.reconciliation_count += 1
        record.reconciliation_matches = True

    after_facts = inspect_records(twin)
    for violation in after_facts.violations:
        if violation not in violations:
            violations.append(violation)
    if len(twin) != len(before):
        violations.append("Record count changed during simulation")
    if {r.id for r in twin} != {r.id for r in before}:
        violations.append("Transaction identity changed during simulation")
    if after_facts.processed != facts.total:
        violations.append("Not every expected record would be completed")

    for old, new in zip(before, twin, strict=True):
        immutable_fields = ("id", "idempotency_key", "amount", "currency", "source_reference")
        if any(getattr(old, name) != getattr(new, name) for name in immutable_fields):
            violations.append("Immutable transaction fields changed during simulation")
            break
        if action == "RESUME_PENDING_ONLY" and old.status == "PROCESSED" and old != new:
            violations.append("Selective recovery modified a previously completed record")
            break

    safe = not violations
    if safe:
        explanation = (
            f"The isolated snapshot preserves {facts.processed} completed records and processes "
            f"{len(target_indices)} pending records, leaving {after_facts.processed} completed with "
            "one processing event and one matching reconciliation entry each. Backend policy and "
            "source-state checks are still required before execution."
        )
    else:
        explanation = (
            f"The proposed action targets {len(target_indices)} of {facts.total} records and creates "
            f"{duplicate_risk} duplicate-processing attempts. Safety checks reject execution; "
            "the simulated effects have not changed operational data."
        )
    return SimulationResult(
        simulation_id=f"SIM-{uuid4()}", exception_id=request.exception_id, strategy=action,
        source_hash=request.source_hash, safe=safe, total_records=facts.total,
        already_processed=facts.processed, pending_records=facts.pending,
        affected_records=len(target_indices), expected_successful_records=after_facts.processed,
        duplicate_attempt_risk=duplicate_risk, policy_violations=violations,
        decision="ELIGIBLE" if safe else "REJECTED", explanation=explanation,
        created_at=datetime.now(timezone.utc),
    )
