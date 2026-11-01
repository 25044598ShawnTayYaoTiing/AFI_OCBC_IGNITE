from app.schemas import InvestigationRequest, InvestigationResponse
from app.simulation.invariants import inspect_records, verified_evidence


def investigate_deterministically(request: InvestigationRequest, reason: str) -> InvestigationResponse:
    facts = inspect_records(request.records)
    eligible = (
        request.exception_type == "PARTIAL_BATCH_FAILURE"
        and facts.processed > 0 and facts.pending > 0 and not facts.violations
    )
    evidence = verified_evidence(facts)
    if request.failure_reason:
        evidence.append(f"Reported batch failure: {request.failure_reason[:1900]}")
    if eligible:
        root_cause = (
            "Batch processing was interrupted after partial completion; the supplied log reports a timeout"
            if request.failure_reason and "timeout" in request.failure_reason.lower()
            else "Batch processing was interrupted after partial completion"
        )
        explanation = (
            f"Stored evidence shows {facts.processed} completed records and {facts.pending} pending records. "
            "Evaluate an unguarded full retry and selective pending-only recovery in the isolated twin. "
            "The failure mechanism is probable; a failed batch status does not undo completed processing. "
            "No recovery has been executed."
        )
        candidates = ["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"]
    else:
        root_cause = {
            "DUPLICATE_TRANSACTION_RECORD": "Duplicate internal processing or source evidence requires human investigation",
            "RECONCILIATION_MISMATCH": "Processing and reconciliation evidence is inconsistent or incomplete",
        }.get(request.exception_type, "Available evidence does not establish a safe partial-batch recovery")
        candidates = ["REQUEST_MANUAL_REVIEW"]
        explanation = (
            "The available evidence does not satisfy the automatic recovery policy. "
            "A reviewer must investigate processing history and reconciliation references. "
            "Human acknowledgement does not authorize modifying completed records or bypassing invariants."
        )
    return InvestigationResponse(
        exception_id=request.exception_id, investigation_status="COMPLETED",
        probable_root_cause=root_cause, evidence=evidence, candidate_actions=candidates,
        requires_additional_evidence=not eligible, explanation=explanation,
        method="DETERMINISTIC_FALLBACK", fallback_reason=reason,
    )
