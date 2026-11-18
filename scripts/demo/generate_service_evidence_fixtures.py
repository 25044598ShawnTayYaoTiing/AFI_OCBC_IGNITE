"""Build reusable, fictional evidence for service request regression runs."""
import argparse
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from pypdf import PdfReader, PdfWriter

from service_request_e2e import pdf


def page(title, fields):
    image = Image.new('RGB', (1000, 1320), 'white')
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1000, 185), fill='#16283e')
    title_font = ImageFont.load_default(size=42)
    body_font = ImageFont.load_default(size=27)
    draw.text((55, 40), title, fill='white', font=title_font)
    draw.text((55, 115), 'SYNTHETIC DEMO DATA - FICTIONAL DOCUMENT', fill='white', font=body_font)
    for index, line in enumerate(fields):
        y = 250 + 92 * index
        draw.text((55, y), line, fill='#16283e', font=body_font)
        draw.line((55, y + 52, 945, y + 52), fill='#d5deea', width=2)
    return image


def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    address = ['Customer Name: Fictional Customer 8', 'Customer Reference: SYN-DEMO-008',
             'Mailing Address: 1 Marina Boulevard #05-01', 'Postal Code: 018989']
    contact = ['Customer Name: Fictional Customer 9', 'Customer Reference: SYN-DEMO-009',
             'New Email: customer9.new@example.invalid', 'New Mobile: +65 8000 1234']
    tax = ['Customer Name: Fictional Customer 10', 'Customer Reference: SYN-DEMO-010',
            'Tax Residency: Exampleland', 'Foreign Tax Country: Exampleland',
            'Tax Identifier: DEMO-JACK-010']
    utility = page('Electricity bill', [*address, 'Statement Date: 2026-11-15', 'Amount Due: SGD 84.20'])
    utility.save(output / '08-electricity-screenshot.png')
    utility.convert('RGBA').save(output / '08-electricity-rgba.png')
    utility.rotate(90, expand=True).save(output / '08-electricity-rotated.jpeg', quality=92)
    photo = Image.new('RGB', (1120, 1450), '#81888b')
    photo.paste(utility, (60, 65))
    photo.rotate(2, fillcolor='#747b7e').save(output / '08-electricity-photo.jpg', quality=90)
    page('Bank statement', [*address[:2], 'Mailing Address: 9 Fictional Road',
                           'Postal Code: 018989']).save(output / '08-bank-conflict.pdf', 'PDF')
    (output / '08-address-proof.pdf').write_bytes(pdf([
        'Supporting Document - Proof of address', 'SYNTHETIC DEMO DATA', *address,
        'Statement Date: 2026-11-15']))
    for kind, category in [('Mailing', 'ADDRESS_CHANGE'), ('Residential', 'RESIDENTIAL_ADDRESS_CHANGE')]:
        (output / f'08-{kind.lower()}-address-request.pdf').write_bytes(pdf([
            'Banking Service Request', 'SYNTHETIC - NOT AN OFFICIAL BANK FORM',
            *address[:2], 'Identity Number: DEMO-ID-SYN-DEMO-008', f'Request Type: {category}',
            f'Existing {kind} Address: 8 Fictional Gardens',
            f'New {kind} Address: 1 Marina Boulevard #05-01',
            f'New {kind} Postal Code: 018989',
            'Declaration Date: 2026-11-18', 'Signature Indicator: Present',
        ]))
    (output / '08-residential-address-proof.pdf').write_bytes(pdf([
        'Supporting Document - Proof of address', 'SYNTHETIC DEMO DATA', *address[:2],
        'Residential Address: 1 Marina Boulevard #05-01', 'Residential Postal Code: 018989',
        'Statement Date: 2026-11-15',
    ]))
    (output / '08-table-address-proof.pdf').write_bytes(pdf([
        'Supporting Document - Proof of address', 'SYNTHETIC DEMO DATA',
        'Customer Full Name    Fictional Customer 8',
        'Customer Reference    SYN-DEMO-008',
        'Mailing Address    1 Marina Boulevard #05-01    Postal Code    018989',
    ]))
    page('Banking Service Request', [
        *address[:2],
        'Identity Number: DEMO-ID-SYN-DEMO-008',
        'Request Type: ADDRESS_CHANGE',
        'New Mailing Address: 1 Marina Boulevard #05-01',
        'New Mailing Postal Code: 018989',
        'Declaration Date: 2026-11-18',
        'Signature Indicator: Present',
    ]).save(output / '08-mailing-address-request-scan.pdf', 'PDF')
    page('Telecommunications bill', [*contact, 'Statement Date: 2024-01-05']).save(
        output / '09-telecom-outdated.jpg', quality=92)
    page('Contact details evidence', contact[:2]).save(output / '09-contact-missing.png')
    page('Contact details evidence', [*contact, 'New Email: conflicting@example.invalid']).save(
        output / '09-contact-ambiguous.png')
    page('Tax residency certificate', [*tax[:2], 'Tax Residency: Elsewhere',
         'Foreign Tax Country: Elsewhere', 'Tax Identifier: DEMO-WRONG']).save(
        output / '10-tax-conflict.png')
    page('Tax residency certificate', tax[:3]).save(
        output / '10-tax-corrected-scan.pdf', 'PDF', save_all=True,
        append_images=[page('Tax residency certificate - page 2', [*tax[:2], *tax[3:]])])
    (output / '10-tax-declaration.pdf').write_bytes(pdf([
        'Tax Residency Declaration', 'SYNTHETIC DEMO DATA', *tax,
        'Declaration Date: 2026-11-18']))

    # Tool-labelled negative PDF fixtures (synthetic data only).
    base_pdf = pdf([
        'Supporting Document - Proof of address', 'SYNTHETIC DEMO DATA', *address,
        'Tool-generated negative fixture: synthetic validation test'])
    encrypted_writer = PdfWriter()
    encrypted_writer.append_pages_from_reader(PdfReader(BytesIO(base_pdf)))
    encrypted_writer.encrypt('synthetic-demo-password')
    with (output / '08-address-proof-encrypted.pdf').open('wb') as handle:
        encrypted_writer.write(handle)

    long_reader = PdfReader(BytesIO(base_pdf))
    long_writer = PdfWriter()
    for _ in range(31):
        long_writer.add_page(long_reader.pages[0])
    with (output / '08-address-proof-31pages.pdf').open('wb') as handle:
        long_writer.write(handle)

    (output / '08-address-proof-truncated.pdf').write_bytes(
        base_pdf[:max(16, len(base_pdf) // 2)])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    generate(parser.parse_args().output)
