from types import SimpleNamespace

import pytest

from app.document_decisions import reconcile


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
    assert reconcile(extraction, {'outcome': 'UNAVAILABLE'}, .80).requires_review
