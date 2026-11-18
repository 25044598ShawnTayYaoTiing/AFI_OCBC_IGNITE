"""Lossless, ordered PDF derivatives. Originals are retained privately by Spring."""
import base64
import io
import warnings
import img2pdf
from PIL import Image, ImageOps
from pypdf import PdfReader
from fastapi import HTTPException
from pydantic import Field
from app.document_intelligence import StrictModel


class ImageConversionRequest(StrictModel):
    images_base64: list[str] = Field(min_length=1, max_length=10)


def decode_images(encoded_pages):
    if not 1 <= len(encoded_pages) <= 10 or sum(map(len, encoded_pages)) > 17_000_000:
        raise HTTPException(422, {'code': 'INVALID_IMAGE', 'message': 'Choose 1-10 image pages, at most 12 MB combined.'})
    pages, total = [], 0
    for encoded in encoded_pages:
        try:
            if len(encoded) > 14_000_000:
                raise ValueError('Image too large')
            raw = base64.b64decode(encoded, validate=True)
            total += len(raw)
            if not 0 < len(raw) <= 10 * 1024 * 1024 or total > 12 * 1024 * 1024:
                raise ValueError('Image limit')
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(raw)) as image:
                    if image.format not in ('JPEG', 'PNG') or image.width * image.height > 25_000_000 or getattr(image, 'n_frames', 1) != 1:
                        raise ValueError('Unsupported image')
                    if min(image.size) < 200:
                        raise HTTPException(422, {'code': 'IMAGE_TOO_SMALL', 'message': 'Upload the original full-page photo or screenshot, at least 200 pixels on each side; thumbnails are too small.'})
                    mime = 'image/jpeg' if image.format == 'JPEG' else 'image/png'
                    image.verify()
                with Image.open(io.BytesIO(raw)) as image:
                    image.load()
            pages.append((raw, mime))
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(422, {'code': 'INVALID_IMAGE', 'message': 'Use complete JPG or PNG images up to 10 MB and 25 megapixels; at most 12 MB combined.'})
    return pages


def convert_images(request: ImageConversionRequest) -> bytes:
    pages = decode_images(request.images_base64)
    try:
        sources = []
        for raw, _ in pages:
            with Image.open(io.BytesIO(raw)) as image:
                # img2pdf handles ordinary EXIF rotations without JPEG re-encoding.
                # Mirrored EXIF transforms need lossless pixel transposition first.
                if image.getexif().get(274) in (2, 4, 5, 7):
                    with ImageOps.exif_transpose(image) as oriented:
                        out = io.BytesIO()
                        if oriented.mode == 'CMYK':
                            oriented = oriented.convert('RGB')
                        oriented.save(out, format='PNG')
                        raw = out.getvalue()
            sources.append(raw)
        pdf = img2pdf.convert(*sources, engine=img2pdf.Engine.internal,
            rotation=img2pdf.Rotation.ifvalid,
            layout_fun=img2pdf.get_fixed_dpi_layout_fun((144, 144)), nodate=True)
        if len(pdf) > 16 * 1024 * 1024 or len(PdfReader(io.BytesIO(pdf), strict=True).pages) != len(pages):
            raise ValueError('Converted PDF limit or page count')
        return pdf
    except Exception:
        # Do not expose image bytes, EXIF, filenames or library exceptions.
        raise HTTPException(503, {'code': 'IMAGE_CONVERSION_FAILED', 'message': 'PDF conversion could not complete. Your originals remain with the upload service; retry the same files.'})
