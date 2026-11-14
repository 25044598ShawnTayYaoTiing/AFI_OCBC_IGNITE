import { useState } from "react";
import type { Detail } from "../document-types";
import { dateTime, label, post } from "../api";
import { ErrorBox, Panel } from "../components";

export function RiskInvestigation({
  detail,
  onUpdate,
}: {
  detail: Detail;
  onUpdate: () => void;
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
    <Panel title="Case risk and decision explanation">
      <div className="panel-body">
        <div className="risk-summary">
          <strong>
            {assessment.score}
            <small>/100</small>
          </strong>
          <div>
            <h3>
              {assessment.level} exception risk
              {detail.submission.risk_hold ? " / On hold" : ""}
            </h3>
            <p>
              Hackathon demonstration policy. Blocking requirements apply
              independently of this score.
            </p>
            <small>
              Evaluated {dateTime(assessment.assessedAt)} / Revision{" "}
              {detail.submission.revision}
            </small>
          </div>
        </div>
        <details>
          <summary>Risk contributions and evidence</summary>
          {assessment.contributions.length ? (
            <ul>
              {assessment.contributions.map((item) => (
                <li key={item.issue}>
                  <strong>
                    +{item.weight} {label(item.issue)}
                  </strong>
                  <p>{item.explanation}</p>
                  <small>
                    {item.ruleId} / Policy version {item.policyVersion}
                  </small>
                  {item.evidence.map((evidence, index) => (
                    <blockquote key={index}>
                      {evidence.field_name}: {evidence.field_value}
                      <small>
                        {" "}
                        {evidence.filename ||
                          evidence.method ||
                          "Backend evidence"}{" "}
                        /{" "}
                        {evidence.page_number
                          ? `page ${evidence.page_number}`
                          : "No PDF page"}
                      </small>
                    </blockquote>
                  ))}
                </li>
              ))}
            </ul>
          ) : (
            <p>No weighted exception findings were recorded.</p>
          )}
        </details>
        <ol className="decision-trace">
          <li>
            <strong>Extract and classify</strong>
            {detail.documents.map((document) => (
              <p key={document.id}>
                {document.filename} / {document.extraction.method} /{" "}
                {document.extraction.requires_review
                  ? "Review required"
                  : "Extraction recorded"}
              </p>
            ))}
          </li>
          <li>
            <strong>Validate and reconcile</strong>
            <p>
              {detail.checks.filter((check) => check.passed).length} of{" "}
              {detail.checks.length} checks satisfied.{" "}
              {assessment.blockingFindings} blocking findings.
            </p>
            <details>
              <summary>Rule results and source evidence</summary>
              {detail.checks.map((check) => (
                <article key={check.check_key}>
                  <h4>
                    {check.outcome || (check.passed ? "PASS" : "FAIL")} /{" "}
                    {check.name}
                  </h4>
                  <small>
                    {check.category || "CGI"} / {check.check_key} / version{" "}
                    {check.rule_version || "historical"}
                  </small>
                  <p>{check.corrective_action}</p>
                  {check.evidence.map((e, index) => (
                    <p key={index}>
                      {e.field_name}: {e.field_value} /{" "}
                      {e.filename || e.method || "Backend policy"}
                      {e.page_number ? ` / page ${e.page_number}` : ""}
                    </p>
                  ))}
                </article>
              ))}
            </details>
          </li>
          <li>
            <strong>Investigate</strong>
            {detail.investigations.length ? (
              detail.investigations.map((investigation, index) => (
                <div key={index}>
                  <p>
                    {investigation.method} /{" "}
                    {investigation.model || "No model reported"} /{" "}
                    {dateTime(investigation.completed_at)}
                  </p>
                  {investigation.recommendations.map((item) => (
                    <p key={item.exception_id}>
                      {item.explanation} Next action: {item.recommended_action}
                    </p>
                  ))}
                </div>
              ))
            ) : (
              <p>
                No AI investigation recorded for this case. Contact verification
                uses deterministic checks.
              </p>
            )}
          </li>
          <li>
            <strong>Resolve and independently verify</strong>
            {detail.automation.map((attempt) => (
              <p key={attempt.id}>
                {attempt.action_type} / {attempt.status} /{" "}
                {dateTime(attempt.completed_at || attempt.created_at)}
              </p>
            ))}
            <p>
              Recorded backend decision: {label(detail.submission.status)}.
              Customer correction attempts: {assessment.correctionAttempts}.
            </p>
          </li>
        </ol>
        {(detail.submission.risk_hold ||
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
