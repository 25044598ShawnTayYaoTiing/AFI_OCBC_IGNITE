import type { Detail } from "../document-types";
import { label } from "../api";
import { caseFacts, nextCaseAction } from "./case-facts";

export function CaseSummary({ detail, onReview, onReadiness }: {
  detail: Detail; onReview: () => void; onReadiness: () => void;
}) {
  const facts = caseFacts(detail);
  const action = nextCaseAction(detail.submission, facts.open.length);
  const blockers = facts.current ? detail.checks.filter(check => !check.passed && check.severity === "BLOCKING").length : 0;
  const priorityIssue = facts.current && !facts.routed ? facts.open[0] : undefined;
  const stages = [
    { name: "Submission", state: "Recorded", done: true },
    { name: "Extraction", state: !detail.documents.length ? "Awaiting PDFs" : facts.uncertain ? "Review required" : "Recorded", done: !!detail.documents.length && !facts.uncertain },
    { name: "Validation", state: facts.current ? `${facts.passed}/${detail.checks.length} passed` : "Check required", done: facts.current && !!detail.checks.length && facts.passed === detail.checks.length },
    { name: "Resolution", state: facts.open.length ? `${facts.open.length} open issues` : facts.current ? "No open issues" : "Awaiting checks", done: facts.current && !facts.open.length },
    { name: "Approval / readiness", state: facts.ready ? "Checks satisfied" : "Not confirmed", done: facts.ready },
    { name: "Operations", state: facts.routed ? "Handover recorded" : "Not handed over", done: facts.routed },
  ];
  return <section className="case-brief" aria-label="Case at a glance">
    <div className="case-brief-top"><div><span className="eyebrow">CUSTOMER REQUEST</span><h2>{label(detail.submission.category)}</h2><p>Case owner: <strong>{detail.submission.owner_name}</strong> · {detail.documents.length} documents · Revision {detail.submission.revision}</p></div><span className={`brief-state ${facts.open.length ? "attention" : ""}`}>{facts.routed ? "With Operations" : facts.open.length ? "Action required" : facts.current ? "Checks recorded" : "Awaiting checks"}</span></div>
    {blockers > 0 && !facts.routed && <p className="context-note"><strong>Cannot approve: {blockers} blocking {blockers === 1 ? "check" : "checks"}.</strong> Review the evidence and required actions below.</p>}
    <div className="case-facts">
      <div><span>Policy checks</span><strong>{facts.current && detail.checks.length ? `${facts.passed} / ${detail.checks.length} passed` : "Not confirmed"}</strong><progress aria-label="Current policy checks passed" max={Math.max(1, detail.checks.length)} value={facts.current ? facts.passed : 0} /></div>
      <div><span>Outstanding issues</span><strong>{facts.open.length}</strong><small>{facts.current ? "Recorded validation findings" : "Earlier findings; recheck required"}</small></div>
      <div><span>Highest open severity</span><strong>{facts.severity}</strong><small>Policy severity, separate from a risk score</small></div>
      <div><span>Risk score</span><strong>{typeof detail.submission.risk_score === "number" ? detail.submission.risk_score : "Not provided"}</strong><small>{typeof detail.submission.risk_score === "number" ? "Recorded by the backend" : "No numerical score in this document workflow"}</small></div>
    </div>
    <div className="case-next-step"><div><span className="eyebrow">NEXT REQUIRED ACTION</span><strong>{priorityIssue?.corrective_action || action}</strong>{priorityIssue && <p><b>Why:</b> {priorityIssue.description || priorityIssue.name}{facts.open.length > 1 ? ` Plus ${facts.open.length - 1} other open issue(s).` : ""}</p>}<p>{facts.open.length ? `${facts.owners.map(label).join(", ") || "Case owner"}: review each issue’s recorded correction and source evidence.` : facts.routed ? "This package is closed to changes. Handover is not an account-opening approval." : "Only the backend can confirm readiness and record a handover."}</p></div>{!facts.routed && <button className="button primary" onClick={facts.open.length || !facts.current ? onReview : onReadiness}>{facts.open.length ? "Review required actions" : !facts.current ? "Review documents" : "Check handover readiness"}</button>}</div>
    <ol className="case-journey" aria-label="Case workflow">{stages.map((stage, index) => <li key={stage.name} className={stage.done ? "done" : "waiting"}><span aria-hidden="true">{stage.done ? "✓" : index + 1}</span><div><strong>{stage.name}</strong><small>{stage.state}</small></div></li>)}</ol>
    <p className="journey-note">Stages reflect recorded evidence and status. Readiness does not imply a separate human or account-opening approval.</p>
  </section>;
}
