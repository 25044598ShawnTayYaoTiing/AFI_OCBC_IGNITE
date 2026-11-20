import { useState } from "react";
import { useAuth, isReviewer } from "../auth";
import type { Detail } from "../document-types";
import { Dialog } from "../ui";
import { reviewState } from "./review-state";

export function CaseDecisionPanel({ detail, busy, onAction, onReview }: {
  detail: Detail;
  busy: boolean;
  onReview: () => void;
  onAction: (action: string, note?: string) => Promise<boolean>;
}) {
  const state = reviewState(detail);
  const { session } = useAuth();
  const [note, setNote] = useState("");
  const [confirmation, setConfirmation] = useState<{ revision: number; action: string }>();
  if (state.routed || state.cancelled) return null;
  const action = state.held && (detail.riskAssessment?.score ?? 100) < 60
    ? "clear-hold"
    : state.authorisation && !state.held ? "authorize-correction"
    : state.eligible ? "approve" : null;
  const allowed = !!action && state.current && isReviewer(session);
  const requiresNote = action !== "approve";
  const actionLabel = action === "clear-hold" ? "Clear review hold"
    : action === "authorize-correction" ? "Send correction request" : "Approve for Operations";
  const revision = detail.submission.revision;
  const reviewEvidence = state.current && !state.pendingVerification &&
    !state.customerTasks.length && (state.open.length > 0 || !detail.documents.length);
  return <section className="case-next" aria-label="Next action">
    <div><h2>Next step</h2><p>{state.next}</p></div>
    {allowed && action ? <>
      <button className="button primary" disabled={busy}
        onClick={() => setConfirmation({ revision, action })}>{actionLabel}</button>
      {confirmation !== undefined && <Dialog label={actionLabel} onClose={() => { if (!busy) setConfirmation(undefined); }} initialFocus="[data-cancel]">
        <h2>{actionLabel}?</h2>
        <p>{(confirmation.revision !== revision || confirmation.action !== action) ? "The case has changed. Close this window and review the latest details."
          : action === "approve" ? "Your approval sends this request to Operations if the final checks pass."
          : action === "clear-hold" ? "Your reason will be recorded. Any remaining critical issue keeps the case on hold."
          : "The customer will be asked to correct the items listed in this case."}</p>
        {requiresNote && <label className="review-note">
          Reason for your decision
          <textarea rows={3} maxLength={1000} value={note}
            onChange={(event) => setNote(event.target.value)} disabled={busy} />
          <small>Enter at least 12 characters.</small>
        </label>}
        <div className="dialog-actions">
          <button data-cancel className="button secondary" disabled={busy} onClick={() => setConfirmation(undefined)}>Go back</button>
          <button className="button primary" disabled={busy || (requiresNote && note.trim().length < 12) || (confirmation.revision !== revision || confirmation.action !== action)} onClick={async () => {
            if (await onAction(action, note)) setNote("");
            setConfirmation(undefined);
          }}>{busy ? "Saving decision…" : actionLabel}</button>
        </div>
      </Dialog>}
    </> : reviewEvidence
      ? <button className="button primary" disabled={busy} onClick={onReview}>
          {detail.documents.length ? "Review evidence" : "Add documents"}
        </button>
      : !state.customerTasks.length &&
        <button className="button primary" disabled={busy} onClick={() => void onAction("validate")}>Check again</button>}
  </section>;
}
