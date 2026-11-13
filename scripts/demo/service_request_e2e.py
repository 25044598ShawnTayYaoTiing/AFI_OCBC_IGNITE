"""Real HTTP workflow, with strict live-Gemini reporting. Never resets or deploys.

The full run is restricted to an explicitly confirmed disposable local stack on port 18080.
AI-only probes do not write to a database. Credentials and OTP codes are never printed.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import sys
import time
import re
import uuid
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
import httpx
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]


def pdf(lines):
    content = 'BT /F1 11 Tf 40 790 Td 17 TL\n'
    for line in lines:
        escaped = line.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')
        content += f'({escaped}) Tj T*\n'
    content += 'ET\n'
    objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               f'<< /Length {len(content.encode("ascii"))} >>\nstream\n{content}endstream']
    result = bytearray(b'%PDF-1.4\n'); offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(result)); result.extend(f'{index} 0 obj\n{obj}\nendobj\n'.encode('ascii'))
    xref = len(result)
    result.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode('ascii'))
    for offset in offsets[1:]:
        result.extend(f'{offset:010d} 00000 n \n'.encode('ascii'))
    result.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode('ascii'))
    return bytes(result)


def support(reference, address):
    return pdf(['Supporting Document', 'SYNTHETIC - NOT AN OFFICIAL BANK FORM',
                'Fictional proof supplied for an existing-customer mailing-address request.',
                'Customer Name: Service Demo Customer', f'Customer Reference: {reference}',
                f'Mailing Address: {address}'])


def call(client, method, path, expected=200, **kwargs):
    response = client.request(method, path, **kwargs)
    if response.status_code != expected:
        try:
            error = response.json().get('code', 'UNKNOWN')
        except ValueError:
            error = 'NON_JSON'
        raise AssertionError(f'{method} {path}: expected {expected}, got {response.status_code} ({error})')
    return None if expected == 204 else response.json()


def ai_probe(base, report):
    sys.path.insert(0, str(ROOT / 'ai-service'))
    from app.config import Settings
    settings = Settings.from_environment()
    with httpx.Client(base_url=base, headers={'X-Service-Key': settings.service_key}, timeout=90) as client:
        before = call(client, 'GET', '/documents/status')
        extracted = call(client, 'POST', '/documents/extract', json={
            'document_id': 'PROBE-' + uuid.uuid4().hex,
            'pdf_base64': base64.b64encode(support('SYN-SERVICE-PROBE', '22 Fictional Service Road')).decode()})
        findings = [{'exception_id': 'EX-SERVICE-PROBE', 'rule_id': 'RULE-SERVICE-PROBE',
                     'name': 'Supporting address differs from the requested address', 'permitted_action': 'REQUEST_CORRECTION',
                     'requires_human': True, 'evidence': [
                         {'evidence_id': 'EV-REQUEST', 'document_id': 'SERVICE-PROBE', 'field': 'mailing_address',
                          'value': '24 Fictional Service Road', 'supporting_text': 'Requested mailing address: 24 Fictional Service Road'},
                         {'evidence_id': 'EV-SUPPORT', 'document_id': extracted['document_id'], 'page': 1, 'field': 'mailing_address',
                          'value': '22 Fictional Service Road', 'supporting_text': 'Mailing Address: 22 Fictional Service Road'}]}]
        investigated = call(client, 'POST', '/documents/investigate', json={'submission_id': 'SERVICE-PROBE', 'revision': 1, 'findings': findings})
        report.update(scope='AI service only; no database or complete workflow exercised', configured=before['configured'],
                      extractionMethod=extracted['method'], extractionFallbackReason=extracted.get('fallback_reason'),
                      investigationMethod=investigated['method'], investigationFallbackReason=investigated.get('fallback_reason'),
                      liveAiVerified=extracted['method'] == investigated['method'] == 'GEMINI')


def workflow(args, report):
    if not args.confirm_disposable or args.base_url.rstrip('/') not in ('http://127.0.0.1:18080', 'http://localhost:18080'):
        raise AssertionError('Full verification requires --confirm-disposable and the dedicated local backend on port 18080, backed by temporary Docker PostgreSQL.')
    load_dotenv(ROOT / '.env', override=False, encoding='utf-8-sig')
    username = 'service-test-' + uuid.uuid4().hex[:14]; password = secrets.token_urlsafe(24)
    reference = 'SYN-SERVICE-' + uuid.uuid4().hex[:12]
    with httpx.Client(base_url=args.base_url, auth=('reviewer', os.getenv('REVIEWER_PASSWORD', 'reviewer-demo')), timeout=600) as staff, \
            httpx.Client(base_url=args.base_url + '/api/customer', timeout=600) as customer:
        call(staff, 'GET', '/actuator/health')
        provisioned = call(staff, 'POST', '/api/documents/customers', json={'username': username, 'password': password,
             'customerName': 'Service Demo Customer', 'customerReference': reference, 'relationshipManager': 'reviewer'})
        session = call(customer, 'POST', '/auth/token', json={'username': username, 'password': password})
        customer.headers['Authorization'] = 'Bearer ' + session['accessToken']
        if args.local_mailbox:
            if args.local_mailbox != 'http://127.0.0.1:18025':
                raise AssertionError('Only the loopback synthetic SMTP sink may be read by this test.')
            call(staff, 'POST', f'/api/documents/customers/{provisioned["id"]}/email', json={'email': 'registered@example.test'})
        profile_before = call(customer, 'GET', '/me')['bankingProfile']
        request = {'requestType': 'ADDRESS_CHANGE', 'mailingAddress': '22 Fictional Service Road',
                   'declarationDate': datetime.now(ZoneInfo('Asia/Singapore')).date().isoformat(), 'authorisation': True}
        initial = call(customer, 'POST', '/eforms', headers={'Idempotency-Key': str(uuid.uuid4())}, json=request)
        case = initial['submission']['id']; report['caseId'] = case
        if args.browser_state:
            args.browser_state.parent.mkdir(parents=True, exist_ok=True)
            args.browser_state.write_text(json.dumps({'baseUrl': args.base_url, 'username': username, 'password': password, 'caseId': case}), encoding='utf-8')
            if os.name != 'nt': args.browser_state.chmod(0o600)
        assert initial['submission']['requestType'] == 'ADDRESS_CHANGE' and len(initial['documents']) == 1
        assert initial['submission']['status'] == 'CORRECTION_REQUIRED'
        notices = call(customer, 'GET', '/notifications')
        assert any(n['submissionId'] == case and n['type'] == 'CORRECTION_REQUIRED' for n in notices)
        bad = call(customer, 'POST', f'/submissions/{case}/documents', headers={'Idempotency-Key': str(uuid.uuid4())},
                   files=[('files', ('supporting-address.pdf', support(reference, '24 Fictional Service Road'), 'application/pdf'))])
        assert bad['submission']['status'] == 'CORRECTION_REQUIRED'
        detail_bad = call(staff, 'GET', f'/api/documents/submissions/{case}')
        assert any(e['check_key'] == 'SERVICE_SUPPORT' and e['status'] == 'OPEN' for e in detail_bad['exceptions'])
        evidence = next(d for d in detail_bad['documents'] if d['document_type'] == 'SUPPORTING_DOCUMENT')
        call(staff, 'POST', f'/api/documents/submissions/{case}/approve', expected=409, json={'revision': bad['submission']['revision']})
        corrected = call(customer, 'POST', f'/submissions/{case}/documents/{evidence["id"]}/replacement',
                         headers={'Idempotency-Key': str(uuid.uuid4())},
                         files=[('files', ('corrected-address.pdf', support(reference, '22 Fictional Service Road'), 'application/pdf'))])
        assert corrected['submission']['status'] == 'RESOLUTION_MANAGEMENT' and not corrected['corrections']
        approved = call(staff, 'POST', f'/api/documents/submissions/{case}/approve', json={'revision': corrected['submission']['revision']})
        assert approved['submission']['status'] == 'OPERATIONS' and len(approved['routing']) == 1
        assert len(next(d for d in approved['documents'] if d['id'] == evidence['id'])['versions']) == 2
        assert call(customer, 'GET', '/me')['bankingProfile'] == profile_before
        final = call(customer, 'GET', f'/submissions/{case}')
        assert final['submission']['status'] == 'OPERATIONS'
        methods = [evidence['extraction']['method'], next(d for d in approved['documents'] if d['id'] == evidence['id'])['extraction']['method']]
        investigations = [d['method'] for d in detail_bad['investigations']]
        report.update(scope='Real HTTP, Spring, PostgreSQL, storage and FastAPI workflow', functionalFlowPassed=True,
                      extractionMethods=methods, investigationMethods=investigations,
                      liveAiVerified=all(m == 'GEMINI' for m in methods) and bool(investigations) and all(m == 'GEMINI' for m in investigations),
                      correctionRequestRecorded=True, replacementVersionRetained=True, rmApprovalRecorded=True,
                      customerPortalStatus='OPERATIONS', externalCoreBankingUpdated=False,
                      correctionEmailStates=sorted({n.get('emailDeliveryStatus') or 'PORTAL_ONLY' for n in notices if n['submissionId'] == case}))
        if args.local_mailbox:
            deadline = time.monotonic() + 25
            while True:
                mailbox = httpx.get(args.local_mailbox + '/messages', timeout=5).json()
                correction_mail = [m for m in mailbox if case in m['body'] and 'correction' in m['subject'].lower()]
                if correction_mail: break
                if time.monotonic() >= deadline: raise AssertionError('No correction notification reached the local SMTP sink.')
                time.sleep(0.5)
            contact = call(customer, 'POST', '/eforms', headers={'Idempotency-Key': str(uuid.uuid4())}, json={
                'requestType': 'CONTACT_UPDATE', 'email': 'updated@example.test',
                'mobile': profile_before['mobile'], 'declarationDate': request['declarationDate'], 'authorisation': True})
            contact_id = contact['submission']['id']
            call(staff, 'POST', f'/api/documents/submissions/{contact_id}/approve', expected=409, json={'revision': contact['submission']['revision']})
            challenge = call(customer, 'POST', f'/submissions/{contact_id}/contact-verification')
            mailbox = httpx.get(args.local_mailbox + '/messages', timeout=5).json()
            received = [m for m in mailbox if m['subject'] == 'AFI Resolve synthetic contact verification' and any('updated@example.test' in recipient for recipient in m['to'])]
            if not received: raise AssertionError('The contact OTP did not reach the local SMTP sink.')
            match = re.search(r'code is: ([0-9]{6})', received[-1]['body'])
            if not match: raise AssertionError('The delivered synthetic OTP was unreadable.')
            verified = call(customer, 'POST', f'/submissions/{contact_id}/contact-verification/confirm', json={'challengeId': challenge['challengeId'], 'code': match.group(1)})
            assert verified['contactVerification']['emailVerified']
            contact_approved = call(staff, 'POST', f'/api/documents/submissions/{contact_id}/approve', json={'revision': verified['submission']['revision']})
            assert contact_approved['submission']['status'] == 'OPERATIONS' and not contact_approved['investigations']
            report.update(localSmtpCorrectionReceived=True, localSmtpOtpReceived=True, contactOtpFlowPassed=True,
                          contactCaseId=contact_id, contactAiUsed=False, externalEmailDeliveryVerified=False)
        call(customer, 'POST', '/auth/logout', expected=204)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--local-mailbox', help='Optional loopback-only synthetic SMTP sink; never a production mailbox.')
    parser.add_argument('--ai-url', default='http://127.0.0.1:18000')
    parser.add_argument('--probe-ai', action='store_true')
    parser.add_argument('--confirm-disposable', action='store_true')
    parser.add_argument('--browser-state', type=Path, help='Ignored private credentials for the optional real browser verification; never prints them.')
    parser.add_argument('--allow-fallback', action='store_true', help='Reports fallback explicitly; never establishes live Gemini success.')
    parser.add_argument('--report', type=Path, default=ROOT / '.local/service-request-e2e.json')
    args = parser.parse_args()
    report = {'timestamp': datetime.now(timezone.utc).isoformat(), 'passed': False}
    try:
        ai_probe(args.ai_url, report) if args.probe_ai else workflow(args, report)
        if not report['liveAiVerified'] and not args.allow_fallback:
            raise AssertionError('Live Gemini was not verified; fallback cannot satisfy the strict test.')
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error) if isinstance(error, AssertionError) else type(error).__name__
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
