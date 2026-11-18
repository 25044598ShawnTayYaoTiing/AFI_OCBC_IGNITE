import base64
import io
import pytest
from PIL import Image
from pypdf import PdfWriter
from fastapi import HTTPException
from app import documents as d
from app.config import Settings

SETTINGS = Settings(service_key='test-service-key', gemini_api_key='test-only', gemini_model='test')
TEXT = 'SYNTHETIC DEMO DATA\nCustomer Information Form\nCustomer Name: Alex Tan'


def image_bytes(fmt='PNG', size=(640, 800)):
    out = io.BytesIO()
    Image.new('RGB', size, 'white').save(out, format=fmt)
    return out.getvalue()


def visual(page_count=1, **changes):
    return d.VisualExtraction(document_type='CUSTOMER_INFORMATION', requires_review=False,
        fields=[d.ModelEvidenceField(field='customer_name', value='Alex Tan', page=1, supporting_text='Customer Name: Alex Tan')],
        **{'pages': [TEXT] * page_count, 'quality': 'READABLE', 'synthetic_label_visible': True, **changes})


@pytest.mark.parametrize('fmt,mime', [('PNG', 'image/png'), ('JPEG', 'image/jpeg')])
def test_actual_image_bytes_use_vision_and_require_review(monkeypatch, fmt, mime):
    raw = image_bytes(fmt)
    def generate(schema, payload, instruction, settings, media):
        assert media[0].inline_data.mime_type == mime
        assert media[0].inline_data.data == raw
        return visual()
    monkeypatch.setattr(d, 'generate', generate)
    result = d.extract(d.ExtractRequest(document_id='image', images_base64=[base64.b64encode(raw).decode()]), SETTINGS)
    assert result.fields[0].value == 'Alex Tan'
    assert result.requires_review is True
    assert 'Human review of the original' in result.notes


def test_group_preserves_order_and_page_citations(monkeypatch):
    raws = [image_bytes('PNG'), image_bytes('JPEG')]
    def generate(*args, media):
        assert [part.inline_data.data for part in media] == raws
        result = visual(2)
        result.fields[0].page = 2
        return result
    monkeypatch.setattr(d, 'generate', generate)
    result = d.extract(d.ExtractRequest(document_id='pages', images_base64=[base64.b64encode(raw).decode() for raw in raws]), SETTINGS)
    assert result.fields[0].page == 2
    assert result.requires_review


@pytest.mark.parametrize('quality', ['BLURRY', 'CROPPED', 'INCOMPLETE', 'UNREADABLE'])
def test_quality_requests_clearer_upload(monkeypatch, quality):
    monkeypatch.setattr(d, 'generate', lambda *args, **kwargs: visual(quality=quality))
    with pytest.raises(HTTPException) as error:
        d.extract_visual('image', [], 1, SETTINGS)
    assert error.value.detail['code'] == 'IMAGE_QUALITY'


@pytest.mark.parametrize('raw', [b'<svg onload="alert(1)"/>', b'\x89PNG\r\n\x1a\ntruncated', image_bytes('GIF')])
def test_unsupported_or_corrupt_images_never_reach_provider(monkeypatch, raw):
    monkeypatch.setattr(d, 'generate', lambda *a, **k: pytest.fail('Invalid image reached provider'))
    with pytest.raises(HTTPException) as error:
        d.extract(d.ExtractRequest(document_id='image', images_base64=[base64.b64encode(raw).decode()]), SETTINGS)
    assert error.value.detail['code'] == 'INVALID_IMAGE'


def test_scanned_pdf_uses_original_pdf_vision(monkeypatch):
    out = io.BytesIO()
    Image.new('RGB', (640, 800), 'white').save(out, format='PDF')
    raw = out.getvalue()
    def generate(*args, media):
        assert media[0].inline_data.mime_type == 'application/pdf'
        assert media[0].inline_data.data == raw
        return visual()
    monkeypatch.setattr(d, 'generate', generate)
    assert d.extract(d.ExtractRequest(document_id='scan', pdf_base64=base64.b64encode(raw).decode()), SETTINGS).requires_review


def test_active_pdf_rejected_before_provider():
    writer = PdfWriter()
    writer.add_blank_page(width=640, height=800)
    writer.add_js('app.alert("unsafe")')
    out = io.BytesIO()
    writer.write(out)
    with pytest.raises(HTTPException) as error:
        d.extract(d.ExtractRequest(document_id='active', pdf_base64=base64.b64encode(out.getvalue()).decode()), SETTINGS)
    assert error.value.detail['code'] == 'INVALID_PDF'


def test_vision_cannot_invent_fields_or_citations(monkeypatch):
    proposed = visual()
    proposed.fields[0].supporting_text = 'Customer Name: Invented Person'
    monkeypatch.setattr(d, 'generate', lambda *args, **kwargs: proposed)
    with pytest.raises(HTTPException) as error:
        d.extract_visual('image', [], 1, SETTINGS)
    assert error.value.detail['code'] == 'EXTRACTION_FAILED'


def test_one_unverified_visual_citation_does_not_discard_grounded_fields(monkeypatch):
    proposed = visual()
    proposed.fields.append(d.ModelEvidenceField(field='postal_code', value='123456', page=1,
                                               supporting_text='Postal Code: 123456'))
    monkeypatch.setattr(d, 'generate', lambda *args, **kwargs: proposed)
    result = d.extract_visual('image', [], 1, SETTINGS)
    assert result.requires_review
    assert [(field.field, field.value) for field in result.fields] == [('customer_name', 'Alex Tan')]
    assert 'Omitted 1 extraction candidate' in result.notes


def test_visual_upload_requires_configured_ai():
    with pytest.raises(HTTPException) as error:
        d.extract_visual('image', [], 1, Settings(service_key='test', gemini_api_key='', gemini_model='test'))
    assert error.value.detail['code'] == 'AI_NOT_CONFIGURED'


def test_repeated_page_headers_do_not_duplicate_fields(monkeypatch):
    proposed = visual(2)
    proposed.fields.append(proposed.fields[0].model_copy(update={'page': 2}))
    monkeypatch.setattr(d, 'generate', lambda *args, **kwargs: proposed)
    result = d.extract_visual('pages', [], 2, SETTINGS)
    assert len(result.fields) == 1
    assert result.requires_review


def test_only_conflicting_readable_evidence_is_held_for_review(monkeypatch):
    proposed = visual(2)
    proposed.pages[1] = TEXT.replace('Alex Tan', 'Alex Lee')
    proposed.fields.append(d.ModelEvidenceField(field='customer_name', value='Alex Lee', page=2,
                                               supporting_text='Customer Name: Alex Lee'))
    monkeypatch.setattr(d, 'generate', lambda *args, **kwargs: proposed)
    result = d.extract_visual('pages', [], 2, SETTINGS)
    assert result.requires_review
    assert not result.fields
    assert [(item.page, item.issue_type) for item in result.observations] == [
        (1, 'UNCERTAIN'), (2, 'UNCERTAIN'),
    ]
