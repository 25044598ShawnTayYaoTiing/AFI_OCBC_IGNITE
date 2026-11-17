import { useEffect, useRef, useState } from "react";
import {
  CheckCircle2,
  ShieldCheck,
  RefreshCw,
  ArrowRight,
  LockKeyhole,
} from "lucide-react";
import type { Detail } from "../document-types";
import { useAuth, isReviewer } from "../auth";
import { Dialog } from "../ui";
import { reviewState } from "./review-state";

export function CaseDecisionPanel({
  detail,
  busy,
  onAction,
  onNavigate,
}: {
  detail: Detail;
  busy: boolean;
  onAction: (action: string, note?: string) => Promise<boolean>;
  onNavigate: (tab: string) => void;
}) {
  const state = reviewState(detail);
  const { session } = useAuth();
  const reviewer = isReviewer(session);
  const [note, setNote] = useState("");
  const [confirmation, setConfirmation] = useState("");
  useEffect(() => {
    setConfirmation("");
    setNote("");
  }, [detail.submission.id, detail.submission.revision]);
  const submitting = useRef(false);
  const [pending, setPending] = useState(false);
  const disabled = busy || pending;
  const action = state.held
    ? "clear-hold"
    : state.authorisation
      ? "authorize-correction"
      : "approve";
  const actionLabel =
    action === "clear-hold"
      ? "Clear reviewed hold"
      : action === "authorize-correction"
        ? "Authorise customer correction"
        : "Approve for Operations";
  const allowed =
    reviewer &&
    state.current &&
    !state.routed &&
    (action === "approve"
      ? state.eligible
      : action === "clear-hold"
        ? (detail.riskAssessment?.score ?? 100) < 60
        : !state.held) &&
    (action === "approve" || note.trim().length >= 12);
  async function confirm() {
    if (submitting.current || disabled || !allowed || confirmation !== action)
      return;
    submitting.current = true;
    setPending(true);
    try {
      const success = await onAction(action, note);
      setConfirmation("");
      if (success) setNote("");
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }
  return (
    <aside className="decision-panel" aria-label="Review decision">
      <div className="decision-heading">
        <ShieldCheck size={20} />
        <h2>Review decision</h2>
      </div>
      <div
        className={`decision-status ${state.routed || state.eligible ? "positive" : "attention"}`}
      >
        <strong>
          {state.routed
            ? "Handover recorded"
            : state.held
              ? "Investigation hold"
              : state.eligible
                ? "Eligible for approval"
                : "Approval blocked"}
        </strong>
        <p>
          {state.routed
            ? "This revision is closed to changes."
            : state.eligible
              ? "Confirm your review before handover."
              : "Resolve the requirements shown in the case outcome."}
        </p>
      </div>
      <dl className="decision-facts">
        <div>
          <dt>Policy blockers</dt>
          <dd>{state.blocking === null ? "Recheck needed" : state.blocking}</dd>
        </div>
        <div>
          <dt>Customer corrections</dt>
          <dd>{state.customerTasks.length}</dd>
        </div>
        <div>
          <dt>Current revision</dt>
          <dd>
            {detail.submission.revision} ·{" "}
            {state.current ? "Checked" : "Not confirmed"}
          </dd>
        </div>
      </dl>
      {!state.routed && (
        <div className="decision-actions">
          {(state.held || state.authorisation) && reviewer && (
            <label className="review-note">
              Reviewer decision note
              <textarea
                rows={3}
                maxLength={1000}
                value={note}
                onChange={(event) => setNote(event.target.value)}
                disabled={disabled}
                placeholder="Record the evidence reviewed and your reason (at least 12 characters)."
              />
              <small>Saved with this revision in the audit history.</small>
            </label>
          )}
          <button
            className="button primary"
            disabled={disabled || !allowed}
            onClick={() => setConfirmation(action)}
          >
            {state.eligible ? (
              <CheckCircle2 size={17} />
            ) : (
              <LockKeyhole size={17} />
            )}
            {actionLabel}
          </button>
          {!reviewer && (
            <p className="decision-hint">
              A reviewer must record this decision. Your role can inspect the
              case.
            </p>
          )}
          {reviewer &&
            state.held &&
            (detail.riskAssessment?.score ?? 100) >= 60 && (
              <p className="decision-hint">
                Resolve high-risk evidence first. The backend also checks
                critical findings before clearing a hold.
              </p>
            )}
          <button
            className="button secondary"
            disabled={disabled || !detail.documents.length}
            onClick={() => void onAction("validate")}
          >
            <RefreshCw size={16} />
            Recheck package
          </button>
          <button
            className="text-link"
            onClick={() =>
              onNavigate(state.open.length ? "exceptions" : "preflight")
            }
          >
            {state.open.length
              ? "Review issues & evidence"
              : "Inspect handover readiness"}
            <ArrowRight size={15} />
          </button>
        </div>
      )}
      <p className="decision-footnote">
        Policy checks control eligibility. AI recommendations are advisory.
        Handover does not confirm banking fulfilment.
      </p>
      {confirmation && (
        <Dialog
          label="Confirm reviewer decision"
          onClose={() => {
            if (!disabled) setConfirmation("");
          }}
          initialFocus="[data-cancel]"
        >
          <div className="decision-confirmation">
            <span className="eyebrow">REVIEWER CONFIRMATION</span>
            <h2>{actionLabel}?</h2>
            <p>
              <strong>{detail.submission.customer_name}</strong> · Revision{" "}
              {detail.submission.revision}
            </p>
            <p>
              {action === "approve"
                ? "This records your approval and asks the backend to hand the current package to Operations. The server will check eligibility again."
                : action === "clear-hold"
                  ? "This records your investigation decision. Unresolved critical evidence or a high risk score will still prevent clearance."
                  : "This authorises the recorded customer correction tasks for this revision. It does not approve the request."}
            </p>
            {note && <blockquote>{note}</blockquote>}
            <p className="decision-hint">
              If the case changes, close this dialog and review the new
              revision.
            </p>
            <div className="dialog-actions">
              <button
                data-cancel
                className="button secondary"
                disabled={disabled}
                onClick={() => setConfirmation("")}
              >
                Cancel
              </button>
              <button
                className="button primary"
                disabled={disabled || !allowed || confirmation !== action}
                onClick={() => void confirm()}
              >
                {disabled ? "Recording decision…" : "Confirm decision"}
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </aside>
  );
}
