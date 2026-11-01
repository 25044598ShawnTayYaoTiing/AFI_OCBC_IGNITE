"""Model output is advisory and cannot expand the deterministic action boundary."""

import logging

from pydantic import ValidationError

from app.agents.gemini import generate_investigation
from app.config import Settings
from app.investigation.fallback import investigate_deterministically
from app.schemas import InvestigationRequest, InvestigationResponse, ModelInvestigation

logger = logging.getLogger(__name__)


def validate_model_proposal(
    proposal: ModelInvestigation, request: InvestigationRequest, baseline: InvestigationResponse,
) -> None:
    if proposal.exception_id != request.exception_id:
        raise ValueError("Model response refers to another exception")
    manual = proposal.candidate_actions == ["REQUEST_MANUAL_REVIEW"]
    if proposal.requires_additional_evidence and not manual:
        raise ValueError("Additional evidence requires manual review")
    if manual and not proposal.requires_additional_evidence:
        raise ValueError("Manual review must identify that more evidence is required")
    if baseline.requires_additional_evidence and not manual:
        raise ValueError("Model cannot override incomplete or contradictory evidence")
    if not manual and set(proposal.candidate_actions) != {"RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"}:
        raise ValueError("A supported partial recovery must compare both recovery strategies")
    if any(item not in baseline.evidence for item in proposal.evidence):
        raise ValueError("Model supplied unverified evidence")


def investigate(request: InvestigationRequest, settings: Settings) -> InvestigationResponse:
    baseline = investigate_deterministically(request, "Gemini API key is not configured")
    if not settings.gemini_api_key:
        return baseline
    if not settings.gemini_model:
        return baseline.model_copy(update={"fallback_reason": "Gemini model is not configured"})
    try:
        proposal = generate_investigation(request, baseline, settings)
        # Revalidate even if an adapter already parsed it, keeping this boundary explicit.
        proposal = ModelInvestigation.model_validate(proposal.model_dump(), strict=True)
        validate_model_proposal(proposal, request, baseline)
    except (ValidationError, ValueError):
        logger.warning("Gemini output rejected by schema or evidence policy; deterministic fallback selected")
        return baseline.model_copy(update={"fallback_reason": "Gemini output failed schema or evidence-policy validation"})
    except Exception:
        # Provider exception text can contain request details, URLs or credentials.
        # Return and log a fixed reason only; an unavailable model never blocks demo investigation.
        logger.warning("Gemini request unavailable; deterministic fallback selected")
        return baseline.model_copy(update={"fallback_reason": "Gemini request failed or timed out"})

    return InvestigationResponse(
        **proposal.model_dump(exclude={"evidence"}),
        evidence=baseline.evidence,
        method="GEMINI",
        fallback_reason=None,
    )
