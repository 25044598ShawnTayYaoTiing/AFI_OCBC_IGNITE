import base64
import io
import pytest
from fastapi import HTTPException
from PIL import Image, ImageOps
from pypdf import PdfReader
from app import image_conversion as c
from app import documents as d
from app.config import Settings


def encode(image, fmt='PNG', **options):
    out = io.BytesIO()
    image.save(out, format=fmt, **options)
    return out.getvalue()


def request(*raws):
    return c.ImageConversionRequest(images_base64=[base64.b64encode(raw).decode() for raw in raws])


def test_order_and_png_pixels_preserved_without_resizing():
    images = [Image.new('RGB', (640, 800), 'red'), Image.new('RGB', (900, 600), 'blue')]
    raw = c.convert_images(request(*(encode(image) for image in images)))
    pdf = PdfReader(io.BytesIO(raw), strict=True)
    assert len(pdf.pages) == 2
    for page, original in zip(pdf.pages, images):
        embedded = page.images[0].image.convert('RGB')
        assert embedded.size == original.size
        assert embedded.tobytes() == original.tobytes()
        assert float(page.mediabox.width) / float(page.mediabox.height) == pytest.approx(original.width / original.height)


def test_jpeg_embedded_without_lossy_recompression_and_exif_rotation():
    exif = Image.Exif()
    exif[274] = 6
    raw = encode(Image.new('RGB', (800, 400), 'green'), 'JPEG', exif=exif)
    pdf = PdfReader(io.BytesIO(c.convert_images(request(raw))))
    image_stream = next(iter(pdf.pages[0]['/Resources']['/XObject'].values())).get_object()
    assert image_stream.get_data() == raw
    page = pdf.pages[0]
    width, height = float(page.mediabox.width), float(page.mediabox.height)
    if page.rotation % 180:
        width, height = height, width
    assert width < height


def test_mirrored_exif_is_losslessly_normalised():
    image = Image.new('RGB', (640, 800), 'white')
    image.paste('red', (0, 0, 200, 800))
    exif = Image.Exif()
    exif[274] = 2
    raw = encode(image, 'JPEG', exif=exif)
    with Image.open(io.BytesIO(raw)) as source:
        expected = ImageOps.exif_transpose(source).convert('RGB')
    pdf = PdfReader(io.BytesIO(c.convert_images(request(raw))))
    assert pdf.pages[0].images[0].image.convert('RGB').tobytes() == expected.tobytes()


def test_conversion_endpoint_requires_auth_and_returns_pdf(client, headers):
    payload = request(encode(Image.new('RGB', (640, 800), 'white'))).model_dump()
    assert client.post('/documents/convert-images', json=payload).status_code == 401
    result = client.post('/documents/convert-images', json=payload, headers=headers)
    assert result.status_code == 200
    assert result.headers['content-type'] == 'application/pdf'
    assert result.headers['x-pdf-page-count'] == '1'
    assert result.headers['cache-control'] == 'no-store'


def test_conversion_failure_is_sanitised_and_retryable(monkeypatch):
    def fail(*args, **kwargs):
        raise RuntimeError('Private EXIF or bytes must not leak')
    monkeypatch.setattr(c.img2pdf, 'convert', fail)
    with pytest.raises(HTTPException) as error:
        c.convert_images(request(encode(Image.new('RGB', (640, 800), 'white'))))
    assert error.value.status_code == 503
    assert error.value.detail['code'] == 'IMAGE_CONVERSION_FAILED'
    assert 'Private EXIF' not in str(error.value.detail)


def test_converted_pdf_is_the_extraction_source_and_retains_page_references(monkeypatch):
    pdf = c.convert_images(request(encode(Image.new('RGB', (640, 800), 'white')), encode(Image.new('RGB', (640, 800), 'gray'))))
    def visual(document_id, media, count, settings):
        assert count == 2
        assert media[0].inline_data.mime_type == 'application/pdf'
        assert media[0].inline_data.data == pdf
        return 'pdf-extracted'
    monkeypatch.setattr(d, 'extract_visual', visual)
    result = d.extract(d.ExtractRequest(document_id='converted', pdf_base64=base64.b64encode(pdf).decode(), converted_from_images=True), Settings('test', 'test', 'test'))
    assert result == 'pdf-extracted'
