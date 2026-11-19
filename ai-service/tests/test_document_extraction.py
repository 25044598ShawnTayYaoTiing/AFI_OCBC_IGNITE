"""Focused extraction regressions: layout variation, missing data and grounding."""
import base64
from types import SimpleNamespace
import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from app import documents as d
from app.config import Settings

class TextPage(dict):
    def __init__(self, text):
        super().__init__()
        self.text = text
    def extract_text(self):
        return self.text



@pytest.fixture(autouse=True)
def unavailable_decision(monkeypatch):
    monkeypatch.setattr(d.document_decisions, 'assess',
                        lambda *args: {'model': 'gemini-decision-1', 'outcome': 'UNAVAILABLE'})


@pytest.mark.parametrize("separator", ["\t", "    "])
def test_labelled_table_cells_are_grounded_without_colons(separator):
    line = separator.join(["Customer Full Name", "Alex Tan", "Postal Code", "018989"])
    proposed = d.ModelExtraction(document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[
        d.ModelEvidenceField(field='customer_name', page=1, value='Alex Tan', supporting_text=line),
        d.ModelEvidenceField(field='postal_code', page=1, value='018989',
                             supporting_text=separator.join(["Postal Code", "018989"])),
    ])
    fields, _ = d.normalise(proposed, [line])
    assert [(f.field, f.value) for f in fields] == [('customer_name', 'Alex Tan'), ('postal_code', '018989')]
    assert all(f.supporting_text in line for f in fields)
    assert d.source_value('customer_name', f'Customer Full Name\nAlex Tan\nPostal Code{separator}018989') == 'Alex Tan'
    assert d.source_value('customer_name', 'Customer Full Name Alex Tan') is None


def test_aliases_and_contiguous_source_values():
    page = "Customer Full Name\nAlex Tan\nMailing Address\n12 Sample Lane\nPostal Code\n000000\nEmail Address\nalex@example.invalid\nContact Number\n6000 0000"
    model = d.ModelExtraction.model_validate({
        'document_type': 'CUSTOMER_INFORMATION', 'requires_review': False,
        'fields': [
            {'field': 'Applicant Full Name', 'value': 'Alex Tan', 'page': 1, 'supporting_text': 'Customer Full Name\nAlex Tan'},
            {'field': 'mailing_address', 'value': '12 Sample Lane, 000000', 'page': 1, 'supporting_text': 'Mailing Address\n12 Sample Lane\nPostal Code\n000000'},
            {'field': 'contact_information', 'value': 'alex@example.invalid, 6000 0000', 'page': 1, 'supporting_text': 'Email Address\nalex@example.invalid\nContact Number\n6000 0000'}]})
    fields, _ = d.normalise(model, [page])
    assert [f.value for f in fields] == ['Alex Tan', '12 Sample Lane', 'alex@example.invalid']
    assert all(f.value in f.supporting_text and f.supporting_text in page for f in fields)
    d.Extraction(document_id='test', method='GEMINI', notes='test', **{**model.model_dump(), 'fields': fields})


@pytest.mark.parametrize('placeholder', ['[NOT COMPLETED]', '[BLANK]', 'Not provided', 'Incomplete'])
def test_incomplete_values_are_observations_not_values(placeholder):
    page = 'Declaration Date: ' + placeholder
    model = d.ModelExtraction(document_type='ACCOUNT_APPLICATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='date signed', value=placeholder, page=1, supporting_text=page)])
    fields, observations = d.normalise(model, [page])
    assert fields == []
    assert observations[0].field == 'declaration_date'
    assert observations[0].supporting_text == page


@pytest.mark.parametrize('support,page', [('Customer Name: Invented', 1), ('Customer Name: Alex', 2)])
def test_rejects_invented_citations_and_pages(support, page):
    model = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='customer_name', value='Alex', page=page, supporting_text=support)])
    fields, _ = d.normalise(model, ['Customer Name: Alex'])
    assert not fields and model.requires_review


def test_closed_schema_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        d.ModelEvidenceField(field='account_password', value='anything', page=1, supporting_text='anything')


def test_existing_customer_service_request_fields_remain_grounded():
    page = 'Banking Service Request\nRequest Type: CONTACT_UPDATE\nNew Email: new@example.invalid\nNew Mobile: +65 8000 1234'
    model = d.ModelExtraction(document_type='SERVICE_REQUEST', requires_review=False, fields=[
        d.ModelEvidenceField(field='request_type', value='CONTACT_UPDATE', page=1, supporting_text='Request Type: CONTACT_UPDATE'),
        d.ModelEvidenceField(field='new email', value='new@example.invalid', page=1, supporting_text='New Email: new@example.invalid'),
        d.ModelEvidenceField(field='new mobile', value='+65 8000 1234', page=1, supporting_text='New Mobile: +65 8000 1234')])
    fields, observations = d.normalise(model, [page])
    assert [f.field for f in fields] == ['request_type', 'email', 'mobile']
    assert not observations
    assert all(f.value in f.supporting_text and f.supporting_text in page for f in fields)


def test_canonical_service_fields_are_not_reclassified_as_legacy_fields():
    page = 'Account Type: SAVINGS\nNew Email: new@example.invalid'
    model = d.ModelExtraction(document_type='SERVICE_REQUEST', requires_review=False, fields=[
        d.ModelEvidenceField(field='account_type', value='SAVINGS', page=1,
                             supporting_text='Account Type: SAVINGS'),
        d.ModelEvidenceField(field='email', value='new@example.invalid', page=1,
                             supporting_text='New Email: new@example.invalid'),
    ])
    fields, _ = d.normalise(model, [page])
    assert [(field.field, field.value) for field in fields] == [
        ('account_type', 'SAVINGS'), ('email', 'new@example.invalid'),
    ]


def test_service_tax_identifiers_are_not_customer_references():
    page = 'Foreign Tax Country: Exampleland\nTIN: DEMO-TIN-123\nTax Declaration: Confirmed'
    model = d.ModelExtraction(document_type='SERVICE_REQUEST', requires_review=False, fields=[
        d.ModelEvidenceField(field='foreign tax country', value='Exampleland', page=1, supporting_text='Foreign Tax Country: Exampleland'),
        d.ModelEvidenceField(field='tin', value='DEMO-TIN-123', page=1, supporting_text='TIN: DEMO-TIN-123'),
        d.ModelEvidenceField(field='tax declaration', value='Confirmed', page=1, supporting_text='Tax Declaration: Confirmed')])
    fields, _ = d.normalise(model, [page])
    assert [f.field for f in fields] == ['foreign_tax_country', 'tax_identifier', 'tax_declaration']


def test_duplicate_fields_require_rejection():
    field = d.ModelEvidenceField(field='customer_name', value='Alex', page=1, supporting_text='Customer Name: Alex')
    model = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False, fields=[field, field])
    fields, _ = d.normalise(model, ['Customer Name: Alex'])
    assert not fields and model.requires_review


def test_varied_synthetic_marker_layout_and_reference(monkeypatch):
    page = 'SYNTHETIC DEMO DATA - Not an OCBC form\nClient Details\nClient ID\nDEMO-CUST-1042'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(is_encrypted=False, trailer={'/Root': {}},
        pages=[TextPage(page)]))
    monkeypatch.setattr(d, 'generate', lambda *a: d.ModelExtraction(document_type='CUSTOMER_INFORMATION',
        fields=[d.ModelEvidenceField(field='Client ID', value='DEMO-CUST-1042', page=1,
            supporting_text='Client ID\nDEMO-CUST-1042')], requires_review=False))
    result = d.extract(d.ExtractRequest(document_id='test', pdf_base64=base64.b64encode(b'%PDF-test').decode()),
        Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert result.method == 'GEMINI'
    assert result.fields[0].field == 'customer_reference'
    assert result.fields[0].value == 'DEMO-CUST-1042'


def test_invalid_pdf_is_distinct():
    with pytest.raises(HTTPException) as error:
        d.extract(d.ExtractRequest(document_id='test', pdf_base64=base64.b64encode(b'bad').decode()),
            Settings(service_key='test', gemini_api_key='', gemini_model='test'))
    assert error.value.detail['code'] == 'INVALID_PDF'


def test_native_electricity_bill_is_supported_with_a_billing_address(monkeypatch):
    page = 'Monthly usage summary\nBilling Address: 12 Sample Lane'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(
        is_encrypted=False, trailer={'/Root': {}}, pages=[TextPage(page)]))
    monkeypatch.setattr(d, 'generate', lambda *args: d.ModelExtraction(
        document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[
            d.ModelEvidenceField(field='mailing_address', value='12 Sample Lane', page=1,
                                 supporting_text='Billing Address: 12 Sample Lane'),
        ]))
    result = d.extract(d.ExtractRequest(document_id='bill', pdf_base64='JVBERi10ZXN0'),
                       Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert result.document_type == 'SUPPORTING_DOCUMENT'
    assert result.fields[0].value == '12 Sample Lane'


def test_signature_annotation_is_normalised_without_inventing_presence():
    support = 'Signature Indicator\nPresent - synthetic demonstration mark'
    model = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='signature', value='Present - synthetic demonstration mark', page=1, supporting_text=support)])
    fields, _ = d.normalise(model, [support])
    assert fields[0].value == 'Present'
    assert fields[0].value in fields[0].supporting_text
    negative = 'Signature Indicator: Not present'
    model.fields[0] = d.ModelEvidenceField(field='signature', value='Not present', page=1, supporting_text=negative)
    assert d.normalise(model, [negative])[0][0].value == 'Not present'


def test_consent_is_not_account_selection():
    with pytest.raises(ValidationError):
        d.ModelEvidenceField(field='terms acknowledged', value='Yes', page=1, supporting_text='Terms Acknowledged: Yes')


def test_http_pdf_error_code_survives_handler(client, headers):
    response = client.post('/documents/extract', headers=headers,
        json={'document_id': 'test', 'pdf_base64': base64.b64encode(b'bad').decode()})
    assert response.status_code == 422
    assert response.json()['code'] == 'INVALID_PDF'


def test_http_extraction_still_requires_service_authentication(client):
    response = client.post('/documents/extract',
        json={'document_id': 'test', 'pdf_base64': base64.b64encode(b'bad').decode()})
    assert response.status_code == 401


@pytest.mark.parametrize('failure', [ValueError('unverified'), RuntimeError('unavailable')])
def test_schema_failure_and_provider_failure_are_distinct(monkeypatch, failure):
    page = 'Client Details\nClient ID\nDEMO-1042'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(is_encrypted=False, trailer={'/Root': {}},
        pages=[TextPage(page)]))
    def fail(*args):
        raise failure
    monkeypatch.setattr(d, 'generate', fail)
    request = d.ExtractRequest(document_id='test', pdf_base64=base64.b64encode(b'%PDF-test').decode())
    settings = Settings(service_key='test', gemini_api_key='test', gemini_model='test')
    if isinstance(failure, ValueError):
        result = d.extract(request, settings)
        assert result.document_type == 'UNKNOWN' and result.requires_review and not result.fields
    else:
        with pytest.raises(HTTPException) as error:
            d.extract(request, settings)
        assert error.value.status_code == 503
        assert error.value.detail['code'] == 'AI_SERVICE_ERROR'


@pytest.mark.parametrize('field,value,source', [
    ('required_selection', 'Present', 'Signature Indicator: Present'),
    ('customer_reference', 'SYN-123', 'Case Reference: SYN-123'),
    ('mailing_address', '123 Example Road', 'Residential Address: 123 Example Road'),
])
def test_rejects_values_cited_from_the_wrong_field(field, value, source):
    proposed = d.ModelExtraction(document_type='ACCOUNT_APPLICATION', requires_review=False,
        fields=[d.ModelEvidenceField(field=field, value=value, page=1, supporting_text=source)])
    fields, _ = d.normalise(proposed, [source])
    assert not fields and proposed.requires_review


def test_discards_advisory_observation_citing_unrelated_field():
    source = 'Signature Indicator: Present'
    proposed = d.ModelExtraction(document_type='ACCOUNT_APPLICATION', requires_review=False,
        fields=[], observations=[d.Observation(field='required_selection', page=1,
            supporting_text=source, issue_type='POTENTIALLY_MISSING')])
    fields, observations = d.normalise(proposed, [source])
    assert fields == []
    assert observations == []


def test_ungrounded_optional_hints_do_not_discard_valid_extracted_fields():
    source = 'Customer Full Name\nAlex Tan'
    proposed = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='customer_name', value='Alex Tan', page=1, supporting_text=source)],
        observations=[d.Observation(field='tax_residency', page=1, supporting_text='Tax Residency', issue_type='POTENTIALLY_MISSING')])
    fields, observations = d.normalise(proposed, [source])
    assert fields[0].value == 'Alex Tan'
    assert observations == []


def test_missing_hint_cannot_hide_a_source_value():
    source = 'Declaration Date: 2026-10-30'
    proposed = d.ModelExtraction(document_type='ACCOUNT_APPLICATION', requires_review=False,
        fields=[], observations=[d.Observation(field='declaration_date', page=1, supporting_text='Declaration Date:', issue_type='POTENTIALLY_MISSING')])
    assert d.normalise(proposed, [source])[1] == []


def test_explicit_missing_marker_with_explanatory_note_is_preserved():
    source = 'Declaration Date\n[NOT COMPLETED]\nIntentionally missing: validation demonstration\nSignature Indicator\nPresent'
    proposed = d.ModelExtraction(document_type='ACCOUNT_APPLICATION', requires_review=False,
        fields=[], observations=[d.Observation(field='declaration_date', page=1,
            supporting_text='Declaration Date\n[NOT COMPLETED]', issue_type='POTENTIALLY_MISSING')])
    assert len(d.normalise(proposed, [source])[1]) == 1


def test_spacing_between_label_and_value_does_not_create_missing_data():
    source = 'Customer Full Name\n\nAlex Tan'
    proposed = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='customer_name', value='Alex Tan', page=1, supporting_text=source)])
    assert d.normalise(proposed, [source])[0][0].value == 'Alex Tan'


def test_new_address_cannot_be_cited_from_the_registered_address_label():
    source = 'Existing Mailing Address: 1 Old Road Singapore 123456\nNew Mailing Address: 640 Rowell Road Singapore 200640'
    proposed = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='new_mailing_address', value='1 Old Road Singapore 123456', page=1,
            supporting_text='Existing Mailing Address: 1 Old Road Singapore 123456')])
    fields, _ = d.normalise(proposed, [source])
    assert not fields and proposed.requires_review


def test_new_address_and_separate_postal_code_retain_their_own_citations():
    source = 'New Mailing Address: 640 Rowell Road\nNew Mailing Postal Code: 200640'
    proposed = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False, fields=[
        d.ModelEvidenceField(field='new_mailing_address', value='640 Rowell Road', page=1, supporting_text='New Mailing Address: 640 Rowell Road'),
        d.ModelEvidenceField(field='new_mailing_postal_code', value='200640', page=1, supporting_text='New Mailing Postal Code: 200640')])
    fields, _ = d.normalise(proposed, [source])
    assert [(field.field, field.value) for field in fields] == [('new_mailing_address', '640 Rowell Road'), ('new_mailing_postal_code', '200640')]


def test_optional_unverified_candidate_does_not_flag_supporting_document_for_review(monkeypatch):
    page = 'Utility Bill\nAccount Holder: Alex Tan\nBilling Address: 109 Rivervale Walk Singapore 540109\nHotline: 1800-000-0000'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(
        is_encrypted=False, trailer={'/Root': {}}, pages=[TextPage(page)]))
    monkeypatch.setattr(d, 'generate', lambda *args: d.ModelExtraction(
        document_type='SUPPORTING_DOCUMENT', requires_review=False, fields=[
            d.ModelEvidenceField(field='customer_name', value='Alex Tan', page=1, supporting_text='Account Holder: Alex Tan'),
            d.ModelEvidenceField(field='mailing_address', value='109 Rivervale Walk Singapore 540109', page=1, supporting_text='Billing Address: 109 Rivervale Walk Singapore 540109'),
            d.ModelEvidenceField(field='contact_information', value='1800-000-0000', page=1, supporting_text='Hotline: 1800-000-0000'),
        ]))
    monkeypatch.setattr(d.document_decisions, 'assess', lambda *args: {
        'model': 'gemini-decision-1', 'outcome': 'ASSESSED', 'request_type': 'ADDRESS_CHANGE',
        'answers': {
            'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.95, 'probabilities': {'SUPPORTING_DOCUMENT': 0.95}},
            'readable': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
            'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95, 'probabilities': {'continue_checks': 0.95}},
            'consistency': {'type': 'score', 'confidence': 0.95, 'score': 2.0, 'probabilities': {'0': 0.01, '1': 0.04, '2': 0.95}},
            'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        },
    })
    res = d.extract(d.ExtractRequest(
        document_id='bill-1', pdf_base64=base64.b64encode(b'%PDF-bill').decode(), request_type='ADDRESS_CHANGE',
    ), Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert not res.requires_review
    assert [(f.field, f.value) for f in res.fields] == [
        ('customer_name', 'Alex Tan'),
        ('mailing_address', '109 Rivervale Walk Singapore 540109'),
    ]


def test_assess_endpoint_refreshes_decision_without_rerunning_extraction(client, headers, monkeypatch):
    monkeypatch.setenv('GEMINI_API_KEY', 'test')
    monkeypatch.setattr(d.document_decisions, 'assess', lambda media, settings, req_type, ctx: {
        'model': 'gemini-decision-1', 'outcome': 'ASSESSED', 'request_type': req_type,
        'answers': {
            'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.96, 'probabilities': {'SUPPORTING_DOCUMENT': 0.96}},
            'readable': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
            'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95, 'probabilities': {'continue_checks': 0.95}},
            'consistency': {'type': 'score', 'confidence': 0.95, 'score': 2.0, 'probabilities': {'0': 0.01, '1': 0.04, '2': 0.95}},
            'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
            'request_corroboration': {'type': 'choice', 'choice': 'consistent', 'confidence': 0.91, 'probabilities': {'consistent': 0.91, 'address_differs': 0.09}},
        },
    })
    resp = client.post('/documents/assess', headers=headers, json={
        'document_id': 'FILE-1',
        'document_type': 'SUPPORTING_DOCUMENT',
        'pdf_base64': base64.b64encode(b'%PDF-1.4\n').decode(),
        'request_type': 'ADDRESS_CHANGE',
        'context': {'request_type': 'ADDRESS_CHANGE', 'requested_mailing_address': '109 Rivervale Walk Singapore 540109'},
        'fields': ['customer_name', 'mailing_address'],
        'requires_review': False,
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body['decision']['outcome'] == 'PASSED'
    assert body['decision']['answers']['request_corroboration']['choice'] == 'consistent'
    assert body['decision']['gating_min_confidence'] == 0.95
    assert body['decision']['advisory_min_confidence'] == 0.91


def test_optional_field_flag_with_model_requires_review_true_does_not_escalate(monkeypatch):
    page = 'Utility Bill\nAccount Holder: Alex Tan\nBilling Address: 109 Rivervale Walk Singapore 540109'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(
        is_encrypted=False, trailer={'/Root': {}}, pages=[TextPage(page)]))
    monkeypatch.setattr(d, 'generate', lambda *args: d.ModelExtraction(
        document_type='SUPPORTING_DOCUMENT',
        requires_review=True,
        uncertain_fields=['contact_information'],
        uncertainty_reasons=['OPTIONAL_FIELD_UNCERTAIN'],
        fields=[
            d.ModelEvidenceField(field='customer_name', value='Alex Tan', page=1, supporting_text='Account Holder: Alex Tan'),
            d.ModelEvidenceField(field='mailing_address', value='109 Rivervale Walk Singapore 540109', page=1, supporting_text='Billing Address: 109 Rivervale Walk Singapore 540109'),
            d.ModelEvidenceField(field='contact_information', value='1800-000-0000', page=1, supporting_text='Hotline: 1800-000-0000'),
        ]))
    monkeypatch.setattr(d.document_decisions, 'assess', lambda *args: {
        'model': 'gemini-decision-1', 'outcome': 'ASSESSED', 'request_type': 'ADDRESS_CHANGE',
        'answers': {
            'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.95, 'probabilities': {'SUPPORTING_DOCUMENT': 0.95}},
            'readable': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
            'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95, 'probabilities': {'continue_checks': 0.95}},
            'consistency': {'type': 'score', 'confidence': 0.95, 'score': 2.0, 'probabilities': {'0': 0.01, '1': 0.04, '2': 0.95}},
            'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        },
    })
    res = d.extract(d.ExtractRequest(
        document_id='bill-opt', pdf_base64=base64.b64encode(b'%PDF-bill').decode(), request_type='ADDRESS_CHANGE',
    ), Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert not res.extraction_requires_review
    assert not res.requires_review
    assert res.uncertainty_reasons == []


def test_assess_endpoint_preserves_extraction_uncertainty_and_clears_contextual(client, headers, monkeypatch):
    monkeypatch.setenv('GEMINI_API_KEY', 'test')
    monkeypatch.setattr(d.document_decisions, 'assess', lambda media, settings, req_type, ctx: {
        'model': 'gemini-decision-1', 'outcome': 'ASSESSED', 'request_type': req_type,
        'answers': {
            'classification': {'type': 'choice', 'choice': 'SUPPORTING_DOCUMENT', 'confidence': 0.96, 'probabilities': {'SUPPORTING_DOCUMENT': 0.96}},
            'readable': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
            'review_route': {'type': 'choice', 'choice': 'continue_checks', 'confidence': 0.95, 'probabilities': {'continue_checks': 0.95}},
            'consistency': {'type': 'score', 'confidence': 0.95, 'score': 2.0, 'probabilities': {'0': 0.01, '1': 0.04, '2': 0.95}},
            'service_relevance': {'type': 'noul', 'confidence': 0.95, 'noul': 0.95},
        },
    })
    res_clear = client.post('/documents/assess', headers=headers, json={
        'document_id': 'doc-clear',
        'document_type': 'SUPPORTING_DOCUMENT',
        'pdf_base64': base64.b64encode(b'%PDF-1.4 test').decode(),
        'request_type': 'ADDRESS_CHANGE',
        'fields': ['customer_name', 'mailing_address'],
        'requires_review': False,
        'extraction_requires_review': False,
    })
    assert res_clear.status_code == 200
    assert res_clear.json()['requires_review'] is False
    assert res_clear.json()['extraction_requires_review'] is False

    res_keep = client.post('/documents/assess', headers=headers, json={
        'document_id': 'doc-keep',
        'document_type': 'SUPPORTING_DOCUMENT',
        'pdf_base64': base64.b64encode(b'%PDF-1.4 test').decode(),
        'request_type': 'ADDRESS_CHANGE',
        'fields': ['customer_name', 'mailing_address'],
        'requires_review': True,
        'extraction_requires_review': True,
        'uncertainty_reasons': ['POOR_DOCUMENT_QUALITY'],
        'quality': 'BLURRY',
    })
    assert res_keep.status_code == 200
    body = res_keep.json()
    assert body['decision']['outcome'] == 'PASSED'
    assert body['extraction_requires_review'] is True
    assert body['requires_review'] is True
    assert 'POOR_DOCUMENT_QUALITY' in body['uncertainty_reasons']
