from app import document_intelligence as intelligence
from app.config import Settings


def request(evidence=None):
    return intelligence.InvestigationRequest(submission_id='case-one', revision=1, findings=[
        intelligence.Finding(exception_id='issue-one', rule_id='rule-one',
            name='Supporting document required', policy_description='Synthetic supporting document required',
            corrective_action='Supply the required synthetic supporting document',
            document_type='SUPPORTING_DOCUMENT', condition_type='ATTACHMENT',
            permitted_action='ATTACH_VERIFIED_DOCUMENT', requires_human=False, evidence=evidence or [])])


def test_fallback_uses_recorded_policy_without_inventing_a_document_subtype():
    result = intelligence.investigate_documents(request(), Settings(service_key='test', gemini_api_key='', gemini_model='test-model'))
    assert result.method == 'DETERMINISTIC_FALLBACK'
    recommendation = result.recommendations[0]
    assert recommendation.explanation == (
        'Supporting document required. Supply the required synthetic supporting document')
    assert recommendation.evidence_ids == []
    assert recommendation.recommended_action == 'ATTACH_VERIFIED_DOCUMENT'


def test_model_cannot_change_policy_action_or_add_a_citation(monkeypatch):
    def fabricated(*args):
        return intelligence.ModelInvestigation(recommendations=[intelligence.Recommendation(
            exception_id='issue-one', explanation='Unverified explanation', evidence_ids=['invented'],
            recommended_action='ATTACH_VERIFIED_DOCUMENT', human_review_required=False)])
    monkeypatch.setattr(intelligence, 'generate', fabricated)
    result = intelligence.investigate_documents(request(), Settings(service_key='test', gemini_api_key='test', gemini_model='test-model'))
    assert result.method == 'DETERMINISTIC_FALLBACK'
    assert result.fallback_reason == 'UNVERIFIED_OUTPUT'
    assert result.recommendations[0].evidence_ids == []
