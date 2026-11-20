import { useState } from "react";
import { useAuth, isReviewer } from "../auth";
import type { Detail } from "../document-types";
import { Dialog } from "../ui";
import { issueTitle } from "./DocumentFindings";
import { reviewState } from "./review-state";

const decisions = [
  { key: "approve", label: "Approve for Processing" },
  { key: "hold", label: "Place on Hold" },
  { key: "request-correction", label: "Request Correction" },
  { key: "cancel", label: "Cancel Case" },
] as const;

type Decision = typeof decisions[number]["key"];
export type DecisionInput = { revision: number; note?: string; resume?: boolean; instructions?: Record<string, string> };

export function CaseDecisionPanel({ detail, busy, error, onAction }: {
  detail: Detail;
  busy: boolean;
  error: string;
  onAction: (action: Decision, input: DecisionInput) => Promise<boolean>;
}) {
  const state = reviewState(detail);
  const { session } = useAuth();
  const [active, setActive] = useState<{ decision: Decision; revision: number; status: string }>();
  const [note, setNote] = useState("");
  const [instructions, setInstructions] = useState<Record<string, string>>({});
  const revision = detail.submission.revision;
  const status = detail.submission.status;
  const investigation = detail.investigations.find(item => item.revision === revision &&
    item.method === "GEMINI" && (!item.rule_set || item.rule_set === detail.ruleSet));
  const corrections = detail.tasks.flatMap(task => {
    const issue = state.open.find(item => item.id === task.exception_id && item.action_type === "REQUEST_CORRECTION");
    return issue && !["RESOLVED", "CANCELLED"].includes(task.status) ? [{ task, issue }] : [];
  });
  if (state.routed || state.cancelled)
    return <p className="case-decision-guidance">{state.next}</p>;
  if (!isReviewer(session)) return <p className="case-decision-guidance">{state.next}</p>;

  const holdNeedsReview = state.held && ((detail.riskAssessment?.score ?? 100) >= 60 ||
    state.failed.some(check => check.check_key.startsWith("PROFILE_") ||
      ["CLASSIFICATION", "INTEGRITY"].includes(check.condition_type || "")));
  const canApprove = state.current && !state.pendingVerification && !holdNeedsReview &&
    state.failed.length === 0 && !state.open.some(issue =>
      issue.severity === "BLOCKING" || issue.condition_type === "CLASSIFICATION");
  const canCorrect = state.current && !state.pendingVerification && !holdNeedsReview && corrections.length > 0;
  const allowed: Record<Decision, boolean> = {
    approve: canApprove,
    hold: state.current && !state.pendingVerification && !detail.submission.staff_suspended,
    "request-correction": canCorrect,
    cancel: true,
  };
  const primary = canApprove ? "approve" : canCorrect && !state.customerTasks.length ? "request-correction" : null;
  const action = decisions.find(item => item.key === active?.decision);
  const stale = active?.revision !== revision || active?.status !== status;

  function choose(decision: Decision) {
    setNote("");
    setInstructions(Object.fromEntries(corrections.map(({ task, issue }) => [
      task.id,
      task.staff_instruction || investigation?.recommendations.find(item =>
        item.exception_id === issue.id && item.recommended_action === "REQUEST_CORRECTION")?.customer_message ||
        task.corrective_action || issue.corrective_action || "",
    ])));
    setActive({ decision, revision, status });
  }

  return <section className="case-next case-decisions" aria-label="Case decisions">
    <div><h2>Case actions</h2><p>{state.next}</p></div>
    <div className="case-action-buttons">
      {decisions.map(item => <button key={item.key} type="button"
        className={item.key === primary ? "button primary" : "button secondary"}
        disabled={busy || !allowed[item.key]} onClick={() => choose(item.key)}>{item.label}</button>)}
    </div>
    {active && action && <Dialog label={action.label} onClose={() => { if (!busy) setActive(undefined); }} initialFocus="[data-back]">
      <h2>{action.label}</h2>
      {stale ? <p>The case has changed. Close this dialog and review the latest details.</p> : <>
        {state.held && ["approve", "request-correction"].includes(action.key) &&
          <p>Confirming this decision lifts the hold.</p>}
        {action.key === "approve" && <p>This completes the case, sends it to Operations and queues an approval email.</p>}
        {action.key === "request-correction" && <>
          <p>Review the instructions before sending them to the customer.</p>
          {corrections.map(({ task, issue }) => <label className="review-note" key={task.id}>
            {issueTitle(issue)}
            <textarea rows={3} required maxLength={1000} disabled={busy} value={instructions[task.id] || ""}
              onChange={event => setInstructions(previous => ({ ...previous, [task.id]: event.target.value }))} />
          </label>)}
        </>}
        {(action.key === "hold" || action.key === "cancel") && <>
          <p>{action.key === "cancel"
            ? "The case will close permanently. The customer will receive the reason you enter."
            : "Processing will pause until Resolution Management makes another decision."}</p>
          <label className="review-note">
            {action.key === "cancel" ? "Reason to share with the customer" : "Reason for suspension"}
            <textarea rows={3} required maxLength={1000} value={note} disabled={busy}
              onChange={event => setNote(event.target.value)} />
          </label>
        </>}
      </>}
      {error && <p role="alert">{error}</p>}
      <div className="dialog-actions">
        <button type="button" data-back className="button secondary" disabled={busy} onClick={() => setActive(undefined)}>Go back</button>
        <button type="button" className="button primary" disabled={busy || stale || !allowed[action.key] ||
          ((action.key === "hold" || action.key === "cancel") && !note.trim()) ||
          (action.key === "request-correction" && corrections.some(({ task }) => !instructions[task.id]?.trim()))}
          onClick={async () => {
            const input = { revision: active.revision, resume: state.held,
              ...(action.key === "request-correction" ? { instructions } : note.trim() ? { note: note.trim() } : {}) };
            if (await onAction(action.key, input)) setActive(undefined);
          }}>
          {busy ? "Saving decision…" : action.key === "cancel" ? "Confirm cancellation" : action.label}
        </button>
      </div>
    </Dialog>}
  </section>;
}
