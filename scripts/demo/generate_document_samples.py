"""Generate illustrative synthetic forms. No customer signatures are generated."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'backend/src/main/resources/synthetic'

def make(filename, title, fields):
    OUT.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(OUT / filename), pagesize=(595, 842))
    c.setTitle('AFI Synthetic - ' + title)
    c.setFillColor(HexColor('#14253b')); c.rect(0, 710, 595, 132, fill=1, stroke=0)
    c.setFillColorRGB(1,1,1); c.setFont('Helvetica-Bold', 19); c.drawString(42, 788, 'AFI Resolve 2.0')
    c.setFont('Helvetica', 14); c.drawString(42, 758, title)
    c.setFillColor(HexColor('#466bd5')); c.setFont('Helvetica-Bold', 10)
    c.drawString(42, 680, 'SYNTHETIC - NOT AN OFFICIAL BANK FORM')
    c.setFillColor(HexColor('#26354a')); c.setFont('Helvetica', 10)
    c.drawString(42, 656, 'Fictional training data. Illustrative AFI intake requirements only.')
    y = 610
    for name, value in fields.items():
        c.setFillColor(HexColor('#26354a')); c.setFont('Helvetica', 11)
        c.drawString(42, y, name.replace('_', ' ').title() + ': ' + (value or '[BLANK]'))
        c.setStrokeColor(HexColor('#e6eaf0')); c.line(42,y-12,553,y-12); y -= 43
    c.setFont('Helvetica', 9); c.setFillColor(HexColor('#7c8799'))
    c.drawString(42, 86, 'Signature Indicator is a test field, not a signature or proof of authenticity.')
    c.drawString(42, 68, 'No connection to live banking systems. No real customer data.'); c.drawRightString(553,42,'1 / 1')
    c.save()

def main():
    common = dict(customer_name='Alex Tan', customer_reference='SYN-ALEX-001', mailing_address='123 Example Road', residential_address='8 Fictional Avenue')
    make('customer-information.pdf', 'Customer Information Form', dict(common, contact_information='alex.tan@example.invalid', signature_indicator='PRESENT'))
    make('account-application.pdf', 'Account Application Form', dict(common, required_selection='PRESENT', signature_indicator='PRESENT'))
    make('tax-residency.pdf', 'Tax Residency Declaration', dict(common, mailing_address='125 Example Road', tax_residency='Singapore (synthetic)', declaration_date='', signature_indicator='PRESENT'))
    make('tax-residency-corrected.pdf', 'Tax Residency Declaration', dict(common, tax_residency='Singapore (synthetic)', declaration_date='2026-10-20', signature_indicator='PRESENT'))
    make('supporting-document.pdf', 'Supporting Document', dict(customer_name='Alex Tan', customer_reference='SYN-ALEX-001', contact_information='Authorised synthetic case repository'))

if __name__ == '__main__': main()
