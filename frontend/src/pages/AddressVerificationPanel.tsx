import type { Detail, Field } from '../document-types';
import { Badge, Panel } from '../components';
import { dateTime, label } from '../api';

export function AddressVerificationPanel({ detail, onInspect }: { detail: Detail; onInspect: (field: Field) => void }) {
  if (!detail.addressVerifications?.length) return null;
  return <Panel title="Address verification" subtitle="OneMap checks the extracted new address where explicitly labelled; the registered address is not used as the proposed value.">
    <div className="panel-body"><p className="context-note">{detail.addressValidationRulesActive?.length ? `Active address checks: ${detail.addressValidationRulesActive.map(label).join(', ')}. Correction or review tasks follow these rules.` : 'Address evidence is available. The OneMap validation rules are awaiting independent reviewer approval in Settings before they affect document routing.'}</p>
      {detail.addressVerifications.map(item => <article className="address-verification-record" key={`${item.evidence.version_id}:${item.evidence.field_name}`}>
        <div className="task-heading"><h3>{label(item.evidence.field_name)} · {item.evidence.filename}</h3><Badge value={item.result.status} /></div>
        <p><strong>Extracted value:</strong> {item.evidence.field_value || 'Not confirmed'}</p><p>{item.result.message}</p>
        {item.evidence.postalEvidence && <p><strong>Separately cited postal code:</strong> {item.evidence.postalEvidence.field_value} <button className="text-link" onClick={() => onInspect(item.evidence.postalEvidence!)}>Open postal-code citation</button></p>}
        {item.result.selected && <div className="address-record"><strong>OneMap record: {item.result.selected.address}</strong><small>Postal code: {item.result.selected.postalCode} · {item.result.selected.latitude}, {item.result.selected.longitude}</small></div>}
        {!!item.result.candidates.length && !item.result.selected && <details className="disclosure"><summary>Compare returned OneMap records</summary>{item.result.candidates.map(candidate => <p key={candidate.id}>{candidate.address}</p>)}</details>}
        <p className="source-evidence">{item.evidence.supporting_text || 'No source quote recorded.'}</p>
        <div className="provenance-line"><span>{item.result.source === 'SYNTHETIC_DEMO' ? 'Demo only — no live verification' : item.result.source === 'ONEMAP_LIVE' ? 'Live OneMap lookup' : 'No live confirmation'} · {dateTime(item.result.checkedAt)} SGT</span><button className="text-link" onClick={() => onInspect(item.evidence)}>Open cited PDF{item.evidence.page_number ? ` · page ${item.evidence.page_number}` : ''}</button></div>
        <small>Unit number, occupancy and supporting-document sufficiency are outside this check.</small>
      </article>)}
    </div>
  </Panel>;
}
