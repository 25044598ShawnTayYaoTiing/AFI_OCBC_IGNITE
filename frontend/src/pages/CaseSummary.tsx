import type { Detail } from "../document-types";
import { label } from "../api";
import { Check, Minus } from "lucide-react";
import { reviewState } from "./review-state";

const riskReasons: Record<string, string> = {
  SUPPORT_MISSING: "Required supporting evidence is missing.",
  SUPPORT_UNREADABLE: "Supporting evidence could not be read clearly.",
  IDENTITY_CONFLICT: "Customer details differ from the bank record.",
  ADDRESS_CONFLICT: "The evidence shows a different address.",
  TAX_CONFLICT: "The evidence shows different tax details.",
  TIN_MISSING: "A required tax identification number is missing.",
  DECLARATION_MISSING: "The declaration is incomplete.",
  REPEATED_CORRECTION: "Earlier corrections did not close all issues.",
  MANIPULATION: "A document failed an integrity check.",
};

export function CaseSummary({ detail }: { detail: Detail }) {
  const state = reviewState(detail);
  const risk = state.current ? detail.riskAssessment : null;
  const investigated = detail.investigations.some((item) => item.revision === detail.submission.revision);
  const verified = detail.automation.some((attempt) => attempt.status === "VERIFIED" &&
    detail.exceptions.some((issue) => issue.id === attempt.exception_id && issue.status === "RESOLVED" &&
      issue.resolution_method === "AUTOMATIC_VERIFIED_ATTACHMENT"));
  const reasons = [...new Set((risk?.contributions || []).slice().sort((a, b) => b.weight - a.weight)
    .map((reason) => riskReasons[reason.issue] || reason.explanation).filter(Boolean))];
  const stages = [
    { name: "Detect", done: state.current },
    { name: "Investigate", done: investigated },
    { name: "Assess risk", done: !!risk },
    { name: "Resolve", done: verified || state.routed },
    { name: "Verify", done: verified || state.routed },
  ];
  return (
    <section className="case-status-summary" aria-label="Case status">
      <div className="case-status-summary-heading">
        <h2>{state.title}</h2>
        {!state.cancelled && <div className="case-risk">
          <strong>{risk ? label(risk.level) + " risk" : "Risk assessment pending"}</strong>
          {risk && <span>{risk.score}/100</span>}
        </div>}
      </div>
      {!state.cancelled && reasons.length > 0 && <p className="case-risk-reason">{reasons.join(" ")}</p>}
      {!state.cancelled && <ol className="case-progress" aria-label="Recorded review stages">
        {stages.map(({ name, done }) => <li key={name} data-done={done}>
          {done ? <Check size={15} aria-hidden="true" /> : <Minus size={15} aria-hidden="true" />}
          {name}<span className="sr-only">{done ? ": complete" : ": not recorded"}</span>
        </li>)}
      </ol>}
    </section>
  );
}
