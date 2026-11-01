"""Minimal live acceptance walkthrough against the real local services/database.

Creates an isolated synthetic demo case; preserves every earlier case and audit.
Run: python scripts/demo/document_acceptance.py
"""
import os
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[2]
BASE=os.getenv('AFI_API','http://127.0.0.1:8080')+'/api/documents'
client=requests.Session()
client.auth=('operator',os.getenv('OPERATOR_PASSWORD','operator-demo'))

def call(method,path,**kwargs):
    response=client.request(method,BASE+path,timeout=90,**kwargs)
    if not response.ok:
        raise AssertionError(f'{method} {path}: {response.status_code} {response.text[:500]}')
    return response.json()

def main():
    data=call('POST','/demo/restore')
    case=data['submission']['id']
    path='/submissions/'+case
    assert data['documents']==[]
    handles=[]
    try:
        for name in ['customer-information.pdf','account-application.pdf','tax-residency.pdf']:
            handle=(ROOT/'backend/src/main/resources/synthetic'/name).open('rb');handles.append(handle)
        data=call('POST',path+'/upload',files=[('files',(h.name.split(os.sep)[-1],h,'application/pdf')) for h in handles])
    finally:
        for h in handles:h.close()
    assert len(data['documents'])==3
    with (ROOT/'backend/src/main/resources/synthetic/customer-information.pdf').open('rb') as pdf:
        assert client.post(BASE+path+'/upload',files=[('files',('duplicate.pdf',pdf,'application/pdf'))],timeout=30).status_code==409
    assert client.post(BASE+path+'/upload',files=[('files',('invalid.pdf',b'not a PDF','application/pdf'))],timeout=30).status_code==400
    assert client.post(BASE+'/rules/draft',json={'sourceId':'none','correctiveAction':'test','severity':'WARNING'},timeout=10).status_code==403
    version=data['documents'][0]['version_id']
    private=client.get(BASE+path+'/versions/'+version+'/pdf',timeout=10)
    assert private.status_code==200 and private.content.startswith(b'%PDF-')
    assert requests.get(BASE+path+'/versions/'+version+'/pdf',timeout=10).status_code==401
    data=call('POST',path+'/validate')
    opened=[e for e in data['exceptions'] if e['status']=='OPEN']
    assert len(opened)==3,[(e['description'],e['status']) for e in data['exceptions']]
    assert data['submission']['status']=='CORRECTION_REQUIRED'
    assert client.post(BASE+path+'/route',timeout=30).status_code==409
    before=call('POST',path+'/preflight')
    assert before['blockingIssues']==3 and not before['actualReady']
    assert before['predictedBlockingIssues']==2 and before['predictedPassedAfterAttachment']>before['checksPassed']
    key='acceptance-'+case
    action=call('POST',path+'/resolve',json={'idempotencyKey':key})
    assert call('POST',path+'/resolve',json={'idempotencyKey':key})==action
    data=call('GET',path)
    assert len(data['documents'])==4
    assert len([e for e in data['exceptions'] if e['status']=='OPEN'])==2
    tax=next(d for d in data['documents'] if d['document_type']=='TAX_RESIDENCY')
    with (ROOT/'backend/src/main/resources/synthetic/tax-residency-corrected.pdf').open('rb') as pdf:
        data=call('POST',path+'/upload?replace='+tax['id'],files=[('files',('tax-residency-corrected.pdf',pdf,'application/pdf'))])
    assert data['submission']['validated_revision'] is None
    data=call('POST',path+'/validate')
    assert data['submission']['status']=='OPERATIONS',data['submission']
    assert all(e['status']=='RESOLVED' for e in data['exceptions'])
    assert all(t['status']=='RESOLVED' for t in data['tasks'])
    assert len(next(d for d in data['documents'] if d['id']==tax['id'])['versions'])==2
    after=call('POST',path+'/preflight')
    assert after['blockingIssues']==0 and after['checksPassed']==after['totalChecks'] and after['actualReady']
    call('POST',path+'/route')
    assert len(call('GET',path)['routing'])==1
    assert call('GET','/analytics')['automaticallyResolved']>=1
    print(f'PASS {case}: {before["checksPassed"]}/{before["totalChecks"]} -> {after["checksPassed"]}/{after["totalChecks"]}; 3 verified resolutions; Operations handover persisted.')
    print('PASS private PDF access, routing guard, idempotent attachment, version history, correction closure and analytics.')

if __name__=='__main__':main()
