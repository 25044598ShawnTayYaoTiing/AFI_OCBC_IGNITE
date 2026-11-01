"""Focused extraction regressions: layout variation, missing data and grounding."""
import base64
from types import SimpleNamespace
import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from app import documents as d
from app.config import Settings


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
    with pytest.raises(ValueError): d.normalise(model, ['Customer Name: Alex'])


def test_closed_schema_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        d.ModelEvidenceField(field='account_password', value='anything', page=1, supporting_text='anything')


def test_duplicate_fields_require_rejection():
    field = d.ModelEvidenceField(field='customer_name', value='Alex', page=1, supporting_text='Customer Name: Alex')
    model = d.ModelExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False, fields=[field, field])
    with pytest.raises(ValueError): d.normalise(model, ['Customer Name: Alex'])


def test_varied_synthetic_marker_layout_and_reference(monkeypatch):
    page = 'SYNTHETIC DEMO DATA - Not an OCBC form\nClient Details\nClient ID\nDEMO-CUST-1042'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(is_encrypted=False,
        pages=[SimpleNamespace(extract_text=lambda: page)]))
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


def test_unsupported_document_does_not_call_gemini(monkeypatch):
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(is_encrypted=False,
        pages=[SimpleNamespace(extract_text=lambda: 'Synthetic demonstration invoice\nAmount: 100')]))
    monkeypatch.setattr(d, 'generate', lambda *a: pytest.fail('Unsupported document reached Gemini'))
    with pytest.raises(HTTPException) as error:
        d.extract(d.ExtractRequest(document_id='test', pdf_base64=base64.b64encode(b'%PDF-test').decode()),
            Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert error.value.detail['code'] == 'UNSUPPORTED_DOCUMENT'


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


@pytest.mark.parametrize('failure,status,code', [(ValueError('unverified'), 422, 'EXTRACTION_FAILED'),
                                               (RuntimeError('unavailable'), 503, 'AI_SERVICE_ERROR')])
def test_schema_failure_and_provider_failure_are_distinct(monkeypatch, failure, status, code):
    page = 'Synthetic demo data\nClient Details\nClient ID\nDEMO-1042'
    monkeypatch.setattr(d, 'PdfReader', lambda *a, **k: SimpleNamespace(is_encrypted=False,
        pages=[SimpleNamespace(extract_text=lambda: page)]))
    def fail(*args):
        raise failure
    monkeypatch.setattr(d, 'generate', fail)
    with pytest.raises(HTTPException) as error:
        d.extract(d.ExtractRequest(document_id='test', pdf_base64=base64.b64encode(b'%PDF-test').decode()),
            Settings(service_key='test', gemini_api_key='test', gemini_model='test'))
    assert error.value.status_code == status
    assert error.value.detail['code'] == code
