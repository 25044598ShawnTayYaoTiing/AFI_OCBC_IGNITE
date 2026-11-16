"""Populate local Operations/Gemini demos through the application API.

Run with requests and python-dotenv installed. Private credentials are read from
.env; existing cases are preserved. A local manifest makes reruns resumable.
"""
import json
import os
from contextlib import ExitStack
from pathlib import Path

import requests
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
load_dotenv(ROOT / '.env', override=False, encoding='utf-8-sig')
BASE = os.getenv('AFI_API', 'http://localhost:5173').rstrip('/')
SAMPLES = ROOT / 'backend/src/main/resources/synthetic'
MANIFEST = ROOT / '.local/document-demo-seed.json'
ORIGINALS = ['customer-information.pdf', 'account-application.pdf', 'tax-residency.pdf']


def main():
    client = requests.Session()
    client.auth = ('operator', os.getenv('OPERATOR_PASSWORD', 'operator-demo'))

    def call(method, path, **kwargs):
        response = client.request(method, BASE + '/api/documents' + path,
                                  timeout=240, **kwargs)
        if not response.ok:
            raise RuntimeError(f'{method} {path}: HTTP {response.status_code} '
                               f'{response.text[:300]}')
        return response.json()

    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    if manifest.get('baseUrl', BASE) != BASE:
        raise RuntimeError('Seed manifest belongs to a different API URL.')
    manifest['baseUrl'] = BASE
    cases = manifest.setdefault('cases', {})

    def save():
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(json.dumps(manifest, indent=2) + '\n')

    def get_case(label, demo=True):
        if label in cases:
            response = client.get(BASE + '/api/documents/submissions/' + cases[label],
                                  timeout=30)
            if response.ok:
                return response.json()
            if response.status_code != 404:
                response.raise_for_status()
        if demo:
            data = call('POST', '/demo/restore')
        else:
            data = call('POST', '/submissions', json={
                'customerName': 'Jamie Lim (Fictional)',
                'customerReference': 'SYN-JAMIE-DRAFT',
                'category': 'ACCOUNT_OPENING',
            })
        cases[label] = data['submission']['id']
        save()
        print(f'Created {label}: {cases[label]}', flush=True)
        return data

    def upload(data, names, replace=None):
        path = '/submissions/' + data['submission']['id'] + '/upload'
        with ExitStack() as stack:
            files = [('files', (name, stack.enter_context((SAMPLES / name).open('rb')),
                                'application/pdf')) for name in names]
            return call('POST', path, files=files,
                        params={'replace': replace} if replace else {})

    before = call('GET', '/ai-status')
    print('Gemini before: ' + json.dumps(before), flush=True)
    results = []
    for label in ['operations-ready', 'customer-corrections', 'incomplete-package', 'draft-intake']:
        data = get_case(label, demo=label != 'draft-intake')
        if label != 'draft-intake' and not data['documents']:
            names = ['customer-information.pdf'] if label == 'incomplete-package' else ORIGINALS
            data = upload(data, names)
        if label == 'operations-ready' and data['submission']['status'] != 'OPERATIONS':
            tax = next(d for d in data['documents'] if d['document_type'] == 'TAX_RESIDENCY')
            data = upload(data, ['tax-residency-corrected.pdf'], replace=tax['id'])
        expected = {'operations-ready': 'OPERATIONS',
                    'customer-corrections': 'CORRECTION_REQUIRED',
                    'incomplete-package': 'CORRECTION_REQUIRED',
                    'draft-intake': 'DRAFT'}[label]
        assert data['submission']['status'] == expected, data['submission']
        if label == 'operations-ready':
            assert len(data['routing']) == 1, 'Operations handover must persist exactly once'
            assert all(t['status'] == 'RESOLVED' for t in data['tasks'])
            assert len(next(d for d in data['documents']
                            if d['document_type'] == 'TAX_RESIDENCY')['versions']) == 2
        if label in ('operations-ready', 'customer-corrections'):
            assert any(a['status'] == 'VERIFIED' for a in data['automation'])
        if label == 'customer-corrections':
            assert len([e for e in data['exceptions'] if e['status'] == 'OPEN']) == 2
        if label != 'draft-intake':
            snapshot = call('POST', '/submissions/' + data['submission']['id'] + '/preflight')
            assert snapshot['actualReady'] == (label == 'operations-ready')
        result = {
            'scenario': label, 'id': data['submission']['id'], 'status': expected,
            'documents': len(data['documents']),
            'openExceptions': sum(e['status'] == 'OPEN' for e in data['exceptions']),
            'extractionMethods': [d['extraction']['method'] for d in data['documents']],
            'investigationMethods': [i['method'] for i in data['investigations']],
            'url': BASE + '/submissions/' + data['submission']['id'],
        }
        results.append(result)
        print(json.dumps(result), flush=True)

    after = call('GET', '/ai-status')
    manifest.update(results=results, aiStatus=after, analytics=call('GET', '/analytics'))
    save()
    print('Gemini after: ' + json.dumps(after), flush=True)
    print('Report: ' + str(MANIFEST), flush=True)


if __name__ == '__main__':
    main()
