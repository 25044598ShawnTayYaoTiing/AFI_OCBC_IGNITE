from types import SimpleNamespace

import pytest

from app.document_decisions import (
    build_context_block,
    build_questions,
    confidence_of,
    reconcile,
)


@pytest.mark.parametrize('readable,kind,expected', [
    (0.97, 'SUPPORTING_DOCUMENT', 'PASSED'),
    (0.03, 'SUPPORTING_DOCUMENT', 'UNCERTAIN'),
    (0.97, 'TAX_RESIDENCY', 'UNCERTAIN'),
])
def test_probability_and_model_agreement_gate(readable, kind, expected):
    extraction = SimpleNamespace(document_type='SUPPORTING_DOCUMENT', requires_review=False)
    answers = {
        'classification': {'choice': kind, 'confidence': .97, 'probabilities': {kind: .97}},
        'readable': {'confidence': .97, 'noul': readable},
        'review_route': {'choice': 'continue_checks', 'confidence': .97,
                         'probabilities': {'continue_checks': .97}},
        'consistency': {'probabilities': {'2': .97}},
    }
    result = reconcile(extraction, {'outcome': 'ASSESSED', 'answers': answers}, .80)
    assert result.decision['outcome'] == expected
    assert result.requires_review == (expected != 'PASSED')


def test_provider_failure_blocks_evidence_approval():
    extraction = SimpleNamespace(requires_review=False)
    result = reconcile(extraction, {'outcome': 'UNAVAILABLE'}, .80)
    assert result.requires_review
    assert 'min_confidence' not in result.decision


def test_service_and_db_questions_and_real_min_confidence_gating():
    ctx = {
        'request_type': 'ADDRESS_CHANGE',
        'customer_name': 'Alex Tan',
        'customer_reference': 'SYN-ALEX-001',
        'requested_mailing_address': '298C Compassvale Street, #08-22, Singapore 543298',
    }
    questions = build_questions('ADDRESS_CHANGE', ctx)
    assert {'service_relevance', 'holder_matches_profile', 'request_corroboration'} <= set(questions)
    block = build_context_block(ctx)
    assert block and '[Customer profile context]' in block and 'SYN-ALEX-001' in block

    base_answers = {
        'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': .95,
                           'probabilities': {'SUPPORTING_DOCUMENT': .95, 'UNKNOWN': .05}},
        'readable': {'type': 'noul', 'confidence': .92, 'noul': .92},
        'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': .91,
                         'probabilities': {'continue_checks': .91, 'manual_review': .09}},
        'consistency': {'type': 'score', 'confidence': .90, 'score': 1.9,
                        'probabilities': {'0': .01, '1': .09, '2': .90}},
        'service_relevance': {'type': 'noul', 'confidence': .93, 'noul': .93},
        'holder_matches_profile': {'type': 'noul', 'confidence': .89, 'noul': .89},
        'request_corroboration': {'type': 'choice', 'choice': 'address_differs', 'confidence': .84,
                                  'probabilities': {'consistent': .10, 'address_differs': .84, 'incomplete': .06}},
    }

    # An address discrepancy keeps outcome PASSED so deterministic policy routes to customer correction
    extraction = SimpleNamespace(
        document_type='SUPPORTING_DOCUMENT', requires_review=False,
        fields=[SimpleNamespace(field='customer_name', value='Alex Tan')],
    )
    res = reconcile(extraction, {'outcome': 'ASSESSED', 'answers': base_answers}, .80)
    assert res.decision['outcome'] == 'PASSED'
    assert not res.requires_review
    assert res.decision['min_confidence'] == 0.84
    assert res.decision['confidence_band'] == 'HIGH'

    # Low route probability triggers UNCERTAIN even when choice is continue_checks
    low_route = {**base_answers, 'review_route': {
        'type': 'choice', 'choice': 'continue_checks', 'confidence': .58,
        'probabilities': {'continue_checks': .58, 'manual_review': .42}}}
    res2 = reconcile(SimpleNamespace(document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[]),
                     {'outcome': 'ASSESSED', 'answers': low_route}, .80)
    assert res2.decision['outcome'] == 'UNCERTAIN'
    assert res2.decision['min_confidence'] == 0.58
    assert res2.decision['confidence_band'] == 'LOW'

    # Unrelated document flagged by service_relevance triggers UNCERTAIN
    irrelevant = {**base_answers, 'service_relevance': {'type': 'noul', 'confidence': .94, 'noul': .06}}
    res3 = reconcile(SimpleNamespace(document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[]),
                     {'outcome': 'ASSESSED', 'answers': irrelevant}, .80)
    assert res3.decision['outcome'] == 'UNCERTAIN'
    assert res3.requires_review

    assert confidence_of({'type': 'noul', 'noul': 0.12}) == pytest.approx(0.88)
