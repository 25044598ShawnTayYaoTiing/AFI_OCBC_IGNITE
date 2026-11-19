import { useState } from "react";
import type { Detail } from "../document-types";
import { dateTime, label, post } from "../api";
import { ErrorBox, Panel } from "../components";
import { advisoryDecisionConfidence, gatingPassProbability, modelMinConfidence } from "./DecisionAssessmentPanel";

export function RiskInvestigation({
  detail,
  onUpdate,
  showActions = true,
}: {
  detail: Detail;
  onUpdate: () => void;
  showActions?: boolean;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const assessment = detail.riskAssessment;
  if (!assessment) return null;
  async function review(action: string) {
    setBusy(true);
    setError("");
    try {
      await post(
        `/api/documents/submissions/${detail.submission.id}/${action}`,
        { revision: detail.submission.revision, note },
      );
      setNote("");
      onUpdate();
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "The decision could not be recorded",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <Panel
      title="Exception risk assessment"
      subtitle={`Evaluated ${dateTime(assessment.assessedAt)} · Revision ${detail.submission.revision}`}
      action={
        <span
          className={`inline-risk-tag risk-tag-${assessment.level.toLowerCase()}`}
        >
          {assessment.level} ({assessment.score}/100)
        </span>
      }
    >
      <div className="panel-body">
        <div className="risk-summary">
          <div className="risk-score-box">
            <strong>
              {assessment.score}
              <small>/100</small>
            </strong>
          </div>
          <div className="risk-summary-copy">
            <h3>
              {label(assessment.level)} exception risk
              {detail.submission.risk_hold ? " · Investigation hold active" : ""}
            </h3>
            <p>
              {assessment.blockingFindings} blocking policy{" "}
              {assessment.blockingFindings === 1 ? "finding" : "findings"} ·{" "}
              {assessment.correctionAttempts} customer resubmission{" "}
              {assessment.correctionAttempts === 1 ? "cycle" : "cycles"}.
            </p>
          </div>
        </div>
        {assessment.contributions.length > 0 && (
          <div className="risk-contributions-grid">
            {assessment.contributions.map((item) => (
              <div className="risk-contribution-card" key={item.issue}>
                <div className="risk-contribution-top">
                  <strong>{label(item.issue)}</strong>
                  <span className="risk-weight-pill">+{item.weight} pts</span>
                </div>
                <p>{item.explanation}</p>
              </div>
            ))}
          </div>
        )}
        <details className="disclosure">
          <summary>Advanced risk &amp; rule trace</summary>
          <div className="disclosure-content">
            <ol className="decision-trace">
              <li>
                <strong>Document extraction</strong>
                {detail.documents.map((document) => (
                  <p key={document.id}>
                    {document.filename} · {label(document.extraction.method)} ·{" "}
                    {document.extraction.requires_review
                      ? "Reviewer confirmation required"
                      : "Verified"}
                    {(() => {
                      const dec = document.extraction.decision;
                      if (!dec) return "";
                      if (dec.outcome === "UNAVAILABLE" || dec.unavailable) {
                        return " · AI assessment unavailable";
                      }
                      const stale =
                        Boolean(dec.stale) ||
                        (typeof dec.context_revision === "number" &&
                          dec.context_revision !== detail.submission.revision);
                      if (stale) {
                        return " · AI assessment awaiting refresh";
                      }
                      const passProb = gatingPassProbability(dec);
                      const modelConf = modelMinConfidence(dec);
                      const advConf = advisoryDecisionConfidence(dec);
                      const failed = dec.failed_criteria?.length
                        ? ` · Failed: ${dec.failed_criteria.join(", ")}`
                        : "";
                      return `${passProb !== null ? ` · Pass probability ${(passProb * 100).toFixed(0)}%` : ""}${modelConf !== null ? ` · Model confidence ${(modelConf * 100).toFixed(0)}%` : ""}${advConf !== null ? ` · Advisory ${(advConf * 100).toFixed(0)}%` : ""}${failed}`;
                    })()}
                  </p>
                ))}
              </li>
              <li>
                <strong>Policy validation</strong>
                <p>
                  {detail.checks.filter((check) => check.passed).length} of{" "}
                  {detail.checks.length} checks passed (
                  {assessment.blockingFindings} blocking).
                </p>
              </li>
              <li>
                <strong>Resolution status</strong>
                <p>
                  Current state: {label(detail.submission.status)}. Correction
                  attempts: {assessment.correctionAttempts}.
                </p>
              </li>
            </ol>
          </div>
        </details>
        {showActions &&
          (detail.submission.risk_hold ||
            detail.tasks.some(
              (task) => task.status === "AWAITING_RESOLVER_AUTHORIZATION",
            )) && (
            <div className="risk-review">
              <label>
                Investigation / customer-contact authorisation note
                <textarea
                  rows={3}
                  maxLength={1000}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                />
              </label>
              <button
                className="button primary"
                disabled={busy || note.trim().length < 12}
                onClick={() =>
                  void review(
                    detail.submission.risk_hold
                      ? "clear-hold"
                      : "authorize-correction",
                  )
                }
              >
                {busy
                  ? "Recording decision..."
                  : detail.submission.risk_hold
                    ? "Clear reviewed hold"
                    : "Authorise customer correction"}
              </button>
              {error && <ErrorBox message={error} />}
            </div>
          )}
      </div>
    </Panel>
  );
}
