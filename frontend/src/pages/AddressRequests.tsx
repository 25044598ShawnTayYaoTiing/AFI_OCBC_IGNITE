import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { post, useResource, label, dateTime } from '../api';
import { useAuth, isReviewer } from '../auth';
import { Badge, Empty, ErrorBox, Loading, Notice, PageHeader, Panel } from '../components';
import type { AddressRequest } from '../address-types';

export function AddressRequests() {
  const result = useResource<AddressRequest[]>('/api/addresses/requests', 15000);
  const { session } = useAuth();
  const [selected, setSelected] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const item = result.data?.find(row => row.id === selected) || result.data?.[0];
  const terminal = item && ['APPROVED', 'REJECTED'].includes(item.status);
  async function act(action: string) {
    if (!item) return;
    setBusy(action); setError(''); setNotice('');
    try {
      const updated = await post<AddressRequest>(`/api/addresses/requests/${encodeURIComponent(item.id)}/${action}`, { note });
      setNotice(updated.status === 'APPROVED' ? 'The revalidated address change was approved and the registered address updated.' : updated.status === 'REJECTED' ? 'The request was rejected with your review note.' : `The request is ${label(updated.status).toLowerCase()}. ${updated.verification.message}`);
      await result.refresh();
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'The action could not be confirmed.'); }
    finally { setBusy(''); }
  }
  return <div className="page-stack"><PageHeader title="Address-change review" description="Compare the registered and proposed addresses, inspect OneMap evidence and record a reviewer decision."><button className="button secondary" onClick={() => void result.refresh()}><RefreshCw size={16} />Refresh</button></PageHeader>
    {(error || result.error) && <ErrorBox message={error || result.error} />}{notice && <Notice>{notice}</Notice>}
    {result.loading ? <Loading /> : !item ? <Panel><Empty title="No address-change requests" description="Customer address requests will appear here. Document-extracted address checks remain inside their original cases." /></Panel> : <div className="issues-layout"><Panel title="Customer requests"><div className="issue-list">{result.data?.map(row => <button key={row.id} className={`issue-option ${row.id === item.id ? 'selected' : ''}`} disabled={!!busy} onClick={() => { setSelected(row.id); setNote(''); setError(''); setNotice(''); }}><span><strong>{row.customerName || row.customerId}</strong><small>{label(row.addressType)} · {row.customerReference}</small></span><Badge value={row.status} /></button>)}</div></Panel><Panel title={`${item.customerName || 'Customer'} · ${label(item.addressType)} address`}><div className="panel-body">
      <div className="provenance-line"><Badge value={item.status} /><Badge value={item.verification.status} /><span>{item.verification.source === 'ONEMAP_LIVE' ? 'Live OneMap evidence' : 'No live OneMap confirmation'}</span></div>
      <div className="address-comparison"><section><h3>Existing registered address</h3><p>{item.previousAddress?.address || 'Not recorded in the customer registry'}</p>{item.previousAddress?.unit && <small>Unit: {item.previousAddress.unit}</small>}</section><section><h3>Requested new address</h3><p>{item.proposedAddress.address}</p><small>Unit: {item.proposedAddress.unit || 'Not provided'} · Postal code: {item.proposedAddress.postalCode}</small></section></div>
      <div className="context-note"><strong>Verification result</strong><p>{item.verification.message}</p><small>Checked {dateTime(item.verification.checkedAt)} SGT · Only the new address was checked. Unit number and occupancy are not confirmed.</small></div>
      {item.verification.selected && <dl className="metadata"><div><dt>OneMap address</dt><dd>{item.verification.selected.address}</dd></div><div><dt>OneMap postal code</dt><dd>{item.verification.selected.postalCode}</dd></div><div><dt>Coordinates</dt><dd>{item.verification.selected.latitude}, {item.verification.selected.longitude}</dd></div></dl>}
      {!!item.verification.candidates.length && !item.verification.selected && <details className="disclosure"><summary>Returned OneMap addresses</summary>{item.verification.candidates.map(candidate => <p key={candidate.id}>{candidate.address}</p>)}</details>}
      <p className="context-note">OneMap verification does not replace supporting-document checks or account-opening approvals. A failed lookup is not proof of an invalid address.</p>
      {item.reviewNote && <p><strong>Recorded reviewer note:</strong> {item.reviewNote}</p>}
      {!terminal && isReviewer(session) && <><label>Reviewer note<textarea value={note} maxLength={1000} onChange={event => setNote(event.target.value)} disabled={!!busy} placeholder="Record your evidence review and decision" /></label><div className="inline-actions"><button className="button secondary" disabled={!!busy || !note.trim()} onClick={() => void act('verify')}>Retry OneMap</button><button className="button primary" disabled={!!busy || !note.trim() || item.verification.status !== 'VERIFIED'} onClick={() => void act('approve')}>{busy === 'approve' ? 'Revalidating...' : 'Approve address change'}</button><button className="button secondary" disabled={!!busy || !note.trim()} onClick={() => void act('reject')}>Reject request</button></div>{item.verification.status !== 'VERIFIED' && <p className="context-note">Approval is blocked until the new address is confirmed. Retry OneMap or investigate the discrepancy; unavailable results remain under manual review.</p>}</>}
      {!terminal && !isReviewer(session) && <Notice>A reviewer must approve or reject this request.</Notice>}
    </div></Panel></div>}
  </div>;
}
