import type { Detail } from "../document-types";
import { label } from "../api";
import { issueTitle } from "./DocumentFindings";
import { requestedFields } from "./case-facts";
import { reviewState } from "./review-state";

const riskReasons: Record<string, string> = {
  SUPPORT_MISSING: "A required supporting document is missing.",
  SUPPORT_UNREADABLE: "Some information could not be read clearly.",
  IDENTITY_CONFLICT: "The customer details differ from the record on file.",
  ADDRESS_CONFLICT: "The supporting document shows a different address.",
  TAX_CONFLICT: "The supporting document shows different tax details.",
  TIN_MISSING: "A tax identification number is missing.",
  DECLARATION_MISSING: "Part of the declaration is missing or incomplete.",
  REPEATED_CORRECTION: "Several corrections have left issues open.",
  MANIPULATION: "The saved document failed an integrity check.",
};

export function CaseSummary({ detail }: { detail: Detail }) {
  const state = reviewState(detail);
  const risk = detail.riskAssessment;
  const resolved = detail.exceptions.filter((item) =>
    item.status === "RESOLVED" && item.resolution_method === "AUTOMATIC_VERIFIED_ATTACHMENT");
  const fields = detail.documents
    .filter(
      (doc) =>
        doc.document_role === "DECLARATION_RECEIPT" ||
        doc.document_role === "REQUEST_DOCUMENT" ||
        (!doc.document_role &&
          doc.document_type !== "SUPPORTING_DOCUMENT" &&
          doc.document_type !== "UNKNOWN"),
    )
    .flatMap((doc) => doc.fields);
  const requested = (requestedFields[detail.submission.category] || []).flatMap((name) => {
    const value = fields.find((field) => field.field_name === name)?.field_value;
    return value ? [{ name, value }] : [];
  });
  const support = detail.checks.find((check) => check.check_key === "SERVICE_SUPPORT");
  const verified = detail.automation.some((attempt) => attempt.status === "VERIFIED");
  const blocked = detail.automation.some((attempt) => attempt.revision === detail.submission.revision && ["BLOCKED", "FAILED"].includes(attempt.status));
  const readByAi = detail.documents.some((doc) => doc.extraction.method === "GEMINI");
  const usedFormChecks = detail.documents.some((doc) => doc.extraction.method === "DETERMINISTIC_FALLBACK");
  const stages = [
    { label: "Detect", complete: state.current || detail.exceptions.length > 0, result: detail.exceptions.length ? "Issues found" : "Checks complete" },
    { label: "Investigate", complete: detail.investigations.length > 0, result: "Evidence reviewed" },
    { label: "Assess risk", complete: !!risk, result: risk ? label(risk.level) + " risk" : "" },
    { label: "Resolve", complete: resolved.length > 0 || verified || state.routed, result: resolved.length || verified ? "Automatic action recorded" : "No open issues at handover" },
    { label: "Verify", complete: verified || state.routed, result: state.routed ? "Sent to Operations" : "Automatic action verified" },
  ];
  const reasons = [...new Set((risk?.contributions || []).slice().sort((a, b) => b.weight - a.weight)
    .map((reason) => riskReasons[reason.issue] || reason.explanation).filter(Boolean))].slice(0, 2);
  return (
    <section className="demo-summary" aria-label="Case outcome">
      <div className="demo-outcome">
        <h2>{state.title}</h2>
        <p>{state.next}</p>
      </div>
      {requested.length > 0 && <dl className="demo-request-values" aria-label="Requested changes">
        {requested.map(({ name, value }) => <div key={name}><dt>{label(name)}</dt><dd>{value}</dd></div>)}
      </dl>}
      <ol className="demo-stages" aria-label="Recorded review progress">
        {stages.map((stage) => <li key={stage.label} data-complete={stage.complete}>
          <strong>{stage.label}</strong><span>{stage.complete ? stage.result : stage.label === "Resolve" && blocked ? "Safety check blocked action" : state.current && !detail.exceptions.length && ["Investigate", "Resolve"].includes(stage.label) ? "No issues to address" : "Not recorded"}</span>
        </li>)}
      </ol>
      <div className="demo-case-facts">
        <section>
          <h3>Document findings</h3>
          {readByAi ? <p>AI read the documents shown in this case.</p> : usedFormChecks && <p>This case used standard form checks.</p>}
          {state.current && support?.outcome === "PASS" && <p>The supporting document meets the service requirements.</p>}
          <p>{state.open.length ? state.open.map(issueTitle).slice(0, 2).join(" · ")
            : state.current ? "No open issues after the latest check." : "The latest request details still need to be checked."}</p>
          {blocked && !verified && <p>Automatic resolution could not pass the safety check. Follow the next step above.</p>}
          {resolved.length > 0 && <p><strong>Resolved automatically:</strong> {resolved.map(issueTitle).join(", ")}.</p>}
        </section>
        <section>
          <h3>Risk assessment</h3>
          {risk ? <>
            <p><strong>{label(risk.level)} risk · {risk.score}/100</strong></p>
            {reasons.length > 0 && <ul>{reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>}
          </> : <p>No risk assessment recorded yet.</p>}
        </section>
      </div>
    </section>
  );
}
