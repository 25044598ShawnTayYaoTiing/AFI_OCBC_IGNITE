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
    # Mandatory gating min confidence (0.90) is separated from advisory corroboration (0.84).
    extraction = SimpleNamespace(
        document_type='SUPPORTING_DOCUMENT', requires_review=False,
        fields=[SimpleNamespace(field='customer_name', value='Alex Tan')],
    )
    res = reconcile(extraction, {'outcome': 'ASSESSED', 'answers': base_answers}, .80)
    assert res.decision['outcome'] == 'PASSED'
    assert not res.requires_review
    assert res.decision['gating_min_confidence'] == 0.90
    assert res.decision['min_confidence'] == 0.90
    assert res.decision['confidence_band'] == 'HIGH'
    assert res.decision['advisory_min_confidence'] == 0.84
    assert res.decision['advisory_confidence_band'] == 'HIGH'

    # Low advisory confidence does not drag down mandatory gating confidence or trigger review
    low_advisory = {**base_answers, 'request_corroboration': {
        'type': 'choice', 'choice': 'incomplete', 'confidence': .45,
        'probabilities': {'incomplete': .45, 'consistent': .35, 'address_differs': .20}}}
    res_adv = reconcile(SimpleNamespace(
        document_type='SUPPORTING_DOCUMENT', requires_review=False,
        fields=[SimpleNamespace(field='customer_name', value='Alex Tan')],
    ), {'outcome': 'ASSESSED', 'answers': low_advisory}, .80)
    assert res_adv.decision['outcome'] == 'PASSED'
    assert res_adv.decision['gating_min_confidence'] == 0.90
    assert res_adv.decision['min_confidence'] == 0.90
    assert res_adv.decision['confidence_band'] == 'HIGH'
    assert res_adv.decision['advisory_min_confidence'] == 0.45
    assert res_adv.decision['advisory_confidence_band'] == 'LOW'

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


def test_decision1_tax_certificate_classification_overrides_tax_self_declaration_overlap():
    extraction = SimpleNamespace(
        document_type='TAX_RESIDENCY',
        requires_review=True,
        fields=[
            SimpleNamespace(field='customer_name', value='User 2'),
            SimpleNamespace(field='foreign_tax_country', value='Australia'),
            SimpleNamespace(field='tax_identifier', value='AUS-QA-02-8741'),
        ],
        observations=[],
        notes='Live Gemini extraction',
    )
    answers = {
        'classification': {
            'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.85,
            'probabilities': {'SUPPORTING_DOCUMENT': 0.85, 'TAX_RESIDENCY': 0.12, 'UNKNOWN': 0.03},
        },
        'readable': {'type': 'noul', 'confidence': 0.96, 'noul': 0.96},
        'review_route': {
            'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.97,
            'probabilities': {'continue_checks': 0.97, 'manual_review': 0.03},
        },
        'consistency': {
            'type': 'score', 'confidence': 0.96, 'score': 1.95,
            'probabilities': {'0': 0.01, '1': 0.03, '2': 0.96},
        },
        'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        'holder_matches_profile': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        'request_corroboration': {
            'type': 'choice', 'choice': 'consistent', 'confidence': 0.97,
            'probabilities': {'consistent': 0.97, 'incomplete': 0.03},
        },
    }
    res = reconcile(
        extraction,
        {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': answers},
        0.70,
    )
    assert res.document_type == 'SUPPORTING_DOCUMENT'
    assert res.decision['outcome'] == 'PASSED'
    assert res.decision['reconciled_from'] == 'TAX_RESIDENCY'
    assert not res.requires_review


def test_tax_certificate_reconciliation_preserves_unrelated_extraction_uncertainty():
    base_answers = {
        'classification': {
            'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.92,
            'probabilities': {'SUPPORTING_DOCUMENT': 0.92, 'TAX_RESIDENCY': 0.08},
        },
        'readable': {'type': 'noul', 'confidence': 0.96, 'noul': 0.96},
        'review_route': {
            'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95,
            'probabilities': {'continue_checks': 0.95, 'manual_review': 0.05},
        },
        'consistency': {
            'type': 'score', 'confidence': 0.94, 'score': 1.9,
            'probabilities': {'0': 0.01, '1': 0.05, '2': 0.94},
        },
        'service_relevance': {'type': 'noul', 'confidence': 0.93, 'noul': 0.93},
    }
    valid_fields = [
        SimpleNamespace(field='customer_name', value='User 2'),
        SimpleNamespace(field='foreign_tax_country', value='Australia'),
        SimpleNamespace(field='tax_identifier', value='AUS-QA-02-8741'),
    ]

    # 1. Blurry/unreadable quality keeps requires_review=True
    blurry = SimpleNamespace(
        document_type='TAX_RESIDENCY', requires_review=True, fields=valid_fields,
        observations=[], notes='Some of the document could not be read clearly.', quality='BLURRY',
    )
    reconcile(blurry, {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': base_answers}, 0.80)
    assert blurry.document_type == 'SUPPORTING_DOCUMENT'
    assert blurry.requires_review is True

    # 2. Missing mandatory TIN keeps requires_review=True even if customer_name & country are present
    missing_tin = SimpleNamespace(
        document_type='TAX_RESIDENCY', requires_review=True,
        fields=valid_fields[:2], observations=[], notes='Live Gemini extraction', quality='READABLE',
    )
    reconcile(missing_tin, {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': base_answers}, 0.80)
    assert missing_tin.document_type == 'SUPPORTING_DOCUMENT'
    assert missing_tin.requires_review is True

    # 3. Unverified/dropped mandatory extraction candidate keeps requires_review=True
    unverified_issue = SimpleNamespace(
        document_type='TAX_RESIDENCY', requires_review=True, fields=valid_fields,
        observations=[], notes='Live Gemini extraction', quality='READABLE',
        extraction_issues=['unverified:tax_identifier'],
    )
    reconcile(unverified_issue, {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': base_answers}, 0.80)
    assert unverified_issue.document_type == 'SUPPORTING_DOCUMENT'
    assert unverified_issue.requires_review is True

    # 4. Conflicting/uncertain observation keeps requires_review=True
    inconsistent_obs = SimpleNamespace(
        document_type='TAX_RESIDENCY', requires_review=True, fields=valid_fields,
        observations=[SimpleNamespace(field='tax_identifier', issue_type='POTENTIALLY_INCONSISTENT')],
        notes='Live Gemini extraction', quality='READABLE',
    )
    reconcile(inconsistent_obs, {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': base_answers}, 0.80)
    assert inconsistent_obs.document_type == 'SUPPORTING_DOCUMENT'
    assert inconsistent_obs.requires_review is True


def test_gating_pass_probability_separated_from_high_model_confidence_rejection():
    extraction = SimpleNamespace(document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[])
    answers = {
        'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.96,
                           'probabilities': {'SUPPORTING_DOCUMENT': 0.96, 'UNKNOWN': 0.04}},
        'readable': {'type': 'noul', 'confidence': 0.94, 'noul': 0.06},
        'review_route': {'type': 'choice', 'choice': 'manual_review', 'confidence': 0.95,
                         'probabilities': {'continue_checks': 0.05, 'manual_review': 0.95}},
        'consistency': {'type': 'score', 'confidence': 0.90, 'score': 0.1,
                        'probabilities': {'0': 0.90, '1': 0.08, '2': 0.02}},
        'service_relevance': {'type': 'noul', 'confidence': 0.93, 'noul': 0.07},
    }
    res = reconcile(extraction, {'outcome': 'ASSESSED', 'answers': answers}, 0.80)
    assert res.decision['outcome'] == 'UNCERTAIN'
    assert res.decision['model_min_confidence'] == 0.90
    assert res.decision['model_confidence_band'] == 'HIGH'
    assert res.decision['gating_pass_probability'] == 0.02
    assert res.decision['gating_min_confidence'] == 0.02
    assert res.decision['min_confidence'] == 0.02
    assert res.decision['confidence_band'] == 'LOW'
    assert res.decision['failed_criteria'] == ['readable', 'review_route', 'consistency', 'service_relevance']
    assert res.requires_review is True


def test_tax_cert_reconciliation_preserves_identity_contradiction_with_extracted_name():
    answers = {
        'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.93,
                           'probabilities': {'SUPPORTING_DOCUMENT': 0.93, 'TAX_RESIDENCY': 0.07}},
        'readable': {'type': 'noul', 'confidence': 0.96, 'noul': 0.96},
        'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95,
                         'probabilities': {'continue_checks': 0.95, 'manual_review': 0.05}},
        'consistency': {'type': 'score', 'confidence': 0.94, 'score': 1.9,
                        'probabilities': {'0': 0.01, '1': 0.05, '2': 0.94}},
        'service_relevance': {'type': 'noul', 'confidence': 0.93, 'noul': 0.93},
        'holder_matches_profile': {'type': 'noul', 'confidence': 0.95, 'noul': 0.05},
        'request_corroboration': {'type': 'choice', 'choice': 'identity_differs', 'confidence': 0.92,
                                  'probabilities': {'consistent': 0.05, 'identity_differs': 0.92}},
    }
    extraction = SimpleNamespace(
        document_type='TAX_RESIDENCY',
        requires_review=True,
        uncertainty_reasons=['MODEL_FLAGGED_REVIEW'],
        fields=[
            SimpleNamespace(field='customer_name', value='Wrong Person'),
            SimpleNamespace(field='foreign_tax_country', value='Australia'),
            SimpleNamespace(field='tax_identifier', value='AUS-QA-02-8741'),
        ],
        observations=[],
        notes='Live Gemini extraction',
        quality='READABLE',
        extraction_issues=[],
    )
    res = reconcile(
        extraction,
        {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': answers},
        0.80,
    )
    assert res.document_type == 'SUPPORTING_DOCUMENT'
    assert res.decision['outcome'] == 'UNCERTAIN'
    assert 'holder_matches_profile' in res.decision['failed_criteria']
    assert 'IDENTITY_CONTRADICTION' in res.uncertainty_reasons
    assert res.extraction_requires_review is False
    assert res.requires_review is True


def test_identity_contradiction_does_not_mark_extraction_requires_review_and_aligns_service_relevance():
    answers = {
        'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.93,
                           'probabilities': {'SUPPORTING_DOCUMENT': 0.93, 'TAX_RESIDENCY': 0.07}},
        'readable': {'type': 'noul', 'confidence': 0.96, 'noul': 0.96},
        'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95,
                         'probabilities': {'continue_checks': 0.95, 'manual_review': 0.05}},
        'consistency': {'type': 'score', 'confidence': 0.94, 'score': 1.9,
                        'probabilities': {'0': 0.01, '1': 0.05, '2': 0.94}},
        'service_relevance': {'type': 'noul', 'confidence': 0.85, 'noul': 0.62},
        'holder_matches_profile': {'type': 'noul', 'confidence': 0.90, 'noul': 0.90},
        'request_corroboration': {'type': 'choice', 'choice': 'identity_differs', 'confidence': 0.91,
                                  'probabilities': {'consistent': 0.04, 'identity_differs': 0.91}},
    }
    extraction = SimpleNamespace(
        document_type='TAX_RESIDENCY',
        requires_review=True,
        uncertainty_reasons=['CLASSIFICATION_AMBIGUOUS'],
        fields=[
            SimpleNamespace(field='customer_name', value='User 2'),
            SimpleNamespace(field='foreign_tax_country', value='Australia'),
            SimpleNamespace(field='tax_identifier', value='AUS-QA-02-8741'),
        ],
        observations=[],
        notes='Live Gemini extraction',
        quality='READABLE',
        extraction_issues=[],
    )
    res_clean = reconcile(
        SimpleNamespace(
            document_type='TAX_RESIDENCY',
            requires_review=True,
            uncertainty_reasons=['CLASSIFICATION_AMBIGUOUS'],
            fields=list(extraction.fields),
            observations=[],
            notes='Live Gemini extraction',
            quality='READABLE',
            extraction_issues=[],
        ),
        {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': {
            **answers,
            'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        }},
        0.80,
    )
    assert res_clean.decision['outcome'] == 'UNCERTAIN'
    assert 'request_corroboration' in res_clean.decision['failed_criteria']
    assert 'IDENTITY_CONTRADICTION' in res_clean.uncertainty_reasons
    assert res_clean.extraction_requires_review is False
    assert res_clean.requires_review is True

    res = reconcile(
        extraction,
        {'outcome': 'ASSESSED', 'request_type': 'TAX_RESIDENCY_UPDATE', 'answers': answers},
        0.80,
    )
    assert res.decision['outcome'] == 'UNCERTAIN'
    assert 'service_relevance' in res.decision['failed_criteria']
    assert 'request_corroboration' in res.decision['failed_criteria']
    assert res.requires_review is True

    # Removing service_relevance failure & identity contradiction passes clean cert even with CLASSIFICATION_AMBIGUOUS
    clean_answers = {
        **answers,
        'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        'request_corroboration': {'type': 'choice', 'choice': 'consistent', 'confidence': 0.95,
                                  'probabilities': {'consistent': 0.95, 'identity_differs': 0.05}},
    }
    clean = SimpleNamespace(
        document_type='TAX_RESIDENCY',
        requires_review=True,
        uncertainty_reasons=['CLASSIFICATION_AMBIGUOUS'],
        fields=list(extraction.fields),
        observations=[],
        notes='Live Gemini extraction',
        quality='READABLE',
        extraction_issues=[],
    )
    res_clean = reconcile(
        clean,
        {'outcome': 'ASSESSED', 'request_type': 'ACCOUNT_OPENING', 'answers': clean_answers},
        0.80,
    )
    assert res_clean.document_type == 'SUPPORTING_DOCUMENT'
    assert res_clean.decision['outcome'] == 'PASSED'
    assert res_clean.requires_review is False
    assert res_clean.extraction_requires_review is False
