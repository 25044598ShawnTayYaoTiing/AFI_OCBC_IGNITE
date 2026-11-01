from copy import deepcopy
from dataclasses import replace
import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from pydantic import ValidationError

from app.agents.gemini import generate_investigation
from app.investigation.fallback import investigate_deterministically
from app.investigation.service import investigate
from app.schemas import InvestigationRequest, ModelInvestigation


def proposal_from_baseline(request):
    baseline = investigate_deterministically(request, "test")
    return ModelInvestigation.model_validate(baseline.model_dump(exclude={"method", "fallback_reason"}))


def test_no_key_transparently_selects_fallback(snapshot, settings):
    response = investigate(InvestigationRequest.model_validate(snapshot), settings)
    assert response.method == "DETERMINISTIC_FALLBACK"
    assert response.candidate_actions == ["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY"]
    assert "800 records are marked processed" in response.evidence
    assert "200 records remain pending" in response.evidence
    assert not response.requires_additional_evidence
    assert response.fallback_reason == "Gemini API key is not configured"


def test_timeout_is_probable_only_when_the_log_reports_it(small_snapshot, settings):
    small_snapshot["failure_reason"] = "Worker interrupted"
    response = investigate(InvestigationRequest.model_validate(small_snapshot), settings)
    assert "timeout" not in response.probable_root_cause.lower()


@pytest.mark.parametrize("exception_type", ["DUPLICATE_TRANSACTION_RECORD", "RECONCILIATION_MISMATCH"])
def test_ambiguous_case_needs_manual_review(small_snapshot, settings, exception_type):
    small_snapshot["exception_type"] = exception_type
    response = investigate(InvestigationRequest.model_validate(small_snapshot), settings)
    assert response.requires_additional_evidence
    assert response.candidate_actions == ["REQUEST_MANUAL_REVIEW"]


def test_missing_records_route_to_human_investigation(small_snapshot, settings):
    small_snapshot["records"] = []
    response = investigate(InvestigationRequest.model_validate(small_snapshot), settings)
    assert response.requires_additional_evidence
    assert response.candidate_actions == ["REQUEST_MANUAL_REVIEW"]


def test_contradictory_records_route_to_human(small_snapshot, settings):
    small_snapshot["records"][0]["processing_count"] = 2
    response = investigate(InvestigationRequest.model_validate(small_snapshot), settings)
    assert response.requires_additional_evidence
    assert any("exactly one processing event" in item for item in response.evidence)


def test_valid_live_model_output_is_labelled_gemini(monkeypatch, small_snapshot, settings):
    request = InvestigationRequest.model_validate(small_snapshot)
    proposal = proposal_from_baseline(request)
    proposal.probable_root_cause = "A worker timeout interrupted the synthetic batch"
    monkeypatch.setattr("app.investigation.service.generate_investigation", lambda *args: proposal)
    response = investigate(request, replace(settings, gemini_api_key="not-a-real-key"))
    assert response.method == "GEMINI"
    assert response.fallback_reason is None
    assert response.probable_root_cause == proposal.probable_root_cause
    assert "7 records are marked processed" in response.evidence


@pytest.mark.parametrize("error", [RuntimeError("secret-in-provider-error"), TimeoutError("secret-in-provider-error")])
def test_unavailable_model_falls_back_without_leaking_secrets(monkeypatch, small_snapshot, settings, caplog, error):
    def unavailable(*args):
        raise error
    monkeypatch.setattr("app.investigation.service.generate_investigation", unavailable)
    response = investigate(InvestigationRequest.model_validate(small_snapshot), replace(settings, gemini_api_key="secret-key"))
    assert response.method == "DETERMINISTIC_FALLBACK"
    assert "failed or timed out" in response.fallback_reason
    assert "secret" not in response.model_dump_json()
    assert "secret" not in caplog.text


@pytest.mark.parametrize("change", [
    {"candidate_actions": ["EXECUTE_TRANSFER"]},
    {"candidate_actions": ["RESUME_PENDING_ONLY", "RESUME_PENDING_ONLY"]},
    {"candidate_actions": ["REQUEST_MANUAL_REVIEW", "RESUME_PENDING_ONLY"]},
    {"requires_additional_evidence": "false"},
    {"sql": "DELETE FROM transactions"},
    {"explanation": ""},
])
def test_malformed_or_unallowlisted_model_json_rejected(small_snapshot, change):
    payload = proposal_from_baseline(InvestigationRequest.model_validate(small_snapshot)).model_dump()
    payload.update(change)
    with pytest.raises(ValidationError):
        ModelInvestigation.model_validate_json(json.dumps(payload), strict=True)


@pytest.mark.parametrize("change", [
    {"exception_id": "ANOTHER-EXCEPTION"},
    {"candidate_actions": ["RESUME_PENDING_ONLY"]},
    {"requires_additional_evidence": True},
    {"evidence": ["All records are already resolved"]},
])
def test_semantically_inconsistent_model_output_rejected(monkeypatch, small_snapshot, settings, change):
    request = InvestigationRequest.model_validate(small_snapshot)
    payload = proposal_from_baseline(request).model_dump()
    payload.update(change)
    proposal = ModelInvestigation.model_validate(payload)
    monkeypatch.setattr("app.investigation.service.generate_investigation", lambda *args: proposal)
    response = investigate(request, replace(settings, gemini_api_key="fake"))
    assert response.method == "DETERMINISTIC_FALLBACK"
    assert "validation" in response.fallback_reason


def test_model_cannot_override_uncertain_evidence(monkeypatch, small_snapshot, settings):
    request = InvestigationRequest.model_validate(small_snapshot)
    proposal = proposal_from_baseline(request)
    request.records[0].reconciliation_matches = False
    monkeypatch.setattr("app.investigation.service.generate_investigation", lambda *args: proposal)
    response = investigate(request, replace(settings, gemini_api_key="fake"))
    assert response.method == "DETERMINISTIC_FALLBACK"
    assert response.candidate_actions == ["REQUEST_MANUAL_REVIEW"]


def test_conservative_model_may_choose_manual_review(monkeypatch, small_snapshot, settings):
    request = InvestigationRequest.model_validate(small_snapshot)
    payload = proposal_from_baseline(request).model_dump()
    payload.update(candidate_actions=["REQUEST_MANUAL_REVIEW"], requires_additional_evidence=True)
    monkeypatch.setattr("app.investigation.service.generate_investigation", lambda *args: ModelInvestigation.model_validate(payload))
    response = investigate(request, replace(settings, gemini_api_key="fake"))
    assert response.method == "GEMINI"
    assert response.candidate_actions == ["REQUEST_MANUAL_REVIEW"]


def test_gemini_adapter_uses_configured_model_json_schema_and_no_tools(monkeypatch, small_snapshot, settings):
    request = InvestigationRequest.model_validate(small_snapshot)
    baseline = investigate_deterministically(request, "test")
    proposal = proposal_from_baseline(request)
    sdk = MagicMock()
    sdk.__enter__.return_value = sdk
    sdk.models.generate_content.return_value = SimpleNamespace(text=proposal.model_dump_json())
    constructor = MagicMock(return_value=sdk)
    monkeypatch.setattr("app.agents.gemini.genai.Client", constructor)
    response = generate_investigation(request, baseline, replace(settings, gemini_api_key="fake-test-key"))
    assert response == proposal
    call = sdk.models.generate_content.call_args.kwargs
    assert call["model"] == settings.gemini_model
    assert call["config"].response_mime_type == "application/json"
    assert call["config"].response_json_schema == ModelInvestigation.model_json_schema()
    assert call["config"].tools is None
    assert call["config"].automatic_function_calling.disable
    assert constructor.call_args.kwargs["http_options"].timeout == 15000
    assert constructor.call_args.kwargs["http_options"].retry_options.attempts == 1
    assert "fake-test-key" not in call["contents"]
    sdk.__exit__.assert_called_once()


def test_adapter_malformed_json_transparently_falls_back(monkeypatch, small_snapshot, settings):
    sdk = MagicMock()
    sdk.__enter__.return_value = sdk
    sdk.models.generate_content.return_value = SimpleNamespace(text='{"candidate_actions":["RUN_SQL"]}')
    monkeypatch.setattr("app.agents.gemini.genai.Client", MagicMock(return_value=sdk))
    response = investigate(InvestigationRequest.model_validate(small_snapshot), replace(settings, gemini_api_key="fake"))
    assert response.method == "DETERMINISTIC_FALLBACK"
    assert "validation" in response.fallback_reason
