import { Check } from "lucide-react";
import { dateTime, detailText, label } from "../api";
import type {
  Detail,
  Finding,
  Field,
  Investigation,
} from "../document-types";
import { Badge, Empty, Panel } from "../components";
import { Disclosure } from "../ui";
import { AuditList, Checklist } from "./DocumentShared";
import { issueCategory } from "./DocumentFindings";

export function CorrectionTasks({
  detail,
  onUpload,
}: {
  detail: Detail;
  onUpload: () => void;
}) {
  return (
    <Panel
      title="Correction tasks"
      subtitle="Track required customer or Resolution Management actions for open findings."
      action={
        !["OPERATIONS", "CANCELLED"].includes(detail.submission.status) && (
          <button className="button secondary" onClick={onUpload}>
            Replace a document
          </button>
        )
      }
    >
      <div className="panel-body">
        {detail.tasks.length ? (
          <div className="task-list">
            {detail.tasks.map((task) => {
              const relatedFinding = detail.exceptions.find(
                (item) => item.id === task.exception_id,
              );
              return (
                <article className="task-card" key={task.id}>
                  <div className="task-heading">
                    <div>
                      {relatedFinding && (
                        <span className="task-category-tag">
                          {issueCategory(relatedFinding)}
                        </span>
                      )}
                      <h3>{task.description}</h3>
                    </div>
                    <div className="task-badges">
                      {task.priority && (
                        <Badge
                          value={task.priority}
                          text={`${label(task.priority)} priority`}
                        />
                      )}
                      <Badge value={task.status} />
                    </div>
                  </div>
                  <div className="task-action-callout">
                    <span className="task-action-label">Required action</span>
                    <p>{task.corrective_action}</p>
                  </div>
                  <div className="task-meta">
                    <div className="task-meta-item">
                      <span className="task-meta-label">Assigned to</span>
                      <strong>{label(task.assigned_role)}</strong>
                    </div>
                    <div className="task-meta-item">
                      <span className="task-meta-label">
                        {task.resolved_at ? "Completed" : "Created"}
                      </span>
                      <strong>
                        {dateTime(task.resolved_at || task.created_at)} SGT
                      </strong>
                    </div>
                  </div>
                  <Disclosure title="Advanced details">
                    <dl className="metadata">
                      <div>
                        <dt>Task ID</dt>
                        <dd className="mono">{task.id}</dd>
                      </div>
                      <div>
                        <dt>Finding reference</dt>
                        <dd className="mono">{task.exception_id}</dd>
                      </div>
                    </dl>
                  </Disclosure>
                </article>
              );
            })}
          </div>
        ) : (
          <Empty
            title="No corrections requested"
            description="Tasks appear when a form needs customer or Resolution Management input."
          />
        )}
      </div>
    </Panel>
  );
}

export function InvestigationExplanation({
  investigation,
  finding,
  detail,
  onInspect,
  inline = false,
}: {
  investigation?: Investigation;
  finding: Finding;
  detail?: Detail;
  onInspect?: (field: Field) => void;
  inline?: boolean;
}) {
  const recommendation = investigation?.recommendations.find(
    (item) => item.exception_id === finding.id,
  );
  const source = investigation?.source_findings?.find(
    (item) => item.exception_id === finding.id,
  );
  if (!investigation || !recommendation)
    return (
      <p className="context-note">
        No AI investigation is recorded for this finding. The policy result
        remains authoritative.
      </p>
    );
  const stale =
    !!detail &&
    (investigation.revision !== detail.submission.revision ||
      (investigation.rule_set != null &&
        investigation.rule_set !== detail.ruleSet));
  const historical = stale || finding.status === "RESOLVED";
  const live = investigation.method === "GEMINI";
  const cited = (source?.evidence || []).filter((e) =>
    recommendation.evidence_ids.includes(e.evidence_id),
  );
  const fields = [...new Set(cited.map((e) => e.field))];
  const comparisons = fields
    .map((field) => ({ field, rows: cited.filter((e) => e.field === field) }))
    .filter(
      (group) =>
        new Set(group.rows.map((row) => row.document_id).filter(Boolean)).size >
        1,
    );
  const content = (
    <div className="investigation-report">
      <div className="provenance-line">
        <Badge value={investigation.method} />
        <Badge
          value={
            recommendation.human_review_required
              ? "HUMAN_INTERVENTION"
              : "POLICY_CONTROLLED"
          }
          text={label(recommendation.recommended_action)}
        />
        <span className="muted small">
          {live ? investigation.model || "Gemini" : "Rule-based analysis"} ·
          Revision {investigation.revision}
        </span>
      </div>
      {historical && (
        <p className="context-note">
          <strong>Historical analysis.</strong>{" "}
          {stale
            ? "The package or rules changed since this run."
            : "This issue has since been resolved."}
        </p>
      )}
      <div className="investigation-summary-block">
        <h4>Analysis summary</h4>
        <p>{recommendation.explanation}</p>
      </div>
      {cited.length > 0 && (
        <div className="investigation-evidence-section">
          <h4>Cited source evidence ({cited.length})</h4>
          <div className="investigation-citations">
            {cited.map((e, index) => {
              const doc = detail?.documents.find(
                (doc) =>
                  doc.id === e.document_id &&
                  doc.versions.some((v) => v.id === e.version_id),
              );
              return (
                <article className="citation-card" key={e.evidence_id}>
                  <div className="citation-card-top">
                    <span className="citation-field">{label(e.field)}</span>
                    <small>
                      {doc?.filename || "System record"}
                      {e.page ? ` · p${e.page}` : ""}
                    </small>
                  </div>
                  <strong className="citation-value">
                    {e.value || "Not confirmed"}
                  </strong>
                  <p className="citation-quote">
                    {e.supporting_text || "No supporting text recorded."}
                  </p>
                  {doc && onInspect && (
                    <button
                      className="text-link"
                      onClick={() =>
                        onInspect({
                          document_id: doc.id,
                          version_id: e.version_id || doc.version_id,
                          filename: doc.filename,
                          field_name: e.field,
                          field_value: e.value,
                          page_number: e.page,
                          supporting_text: e.supporting_text,
                        })
                      }
                    >
                      Open source document ({index + 1})
                    </button>
                  )}
                </article>
              );
            })}
          </div>
        </div>
      )}
      {comparisons.length > 0 && (
        <div className="investigation-comparison-block">
          <h4>Cross-document discrepancy</h4>
          {comparisons.map((group) => (
            <div className="discrepancy-group" key={group.field}>
              <span className="citation-field">{label(group.field)}</span>
              <ul>
                {group.rows.map((row) => (
                  <li key={row.evidence_id}>
                    <span>
                      {detail?.documents.find(
                        (doc) => doc.id === row.document_id,
                      )?.filename || "Source document"}
                      {row.page ? ` (p${row.page})` : ""}:
                    </span>{" "}
                    <strong>{row.value || "Not confirmed"}</strong>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
      <div className="task-action-callout">
        <span className="task-action-label">Recommended next step</span>
        <p>
          {finding.status === "RESOLVED"
            ? "Resolved and verified. No further correction required for this finding."
            : finding.corrective_action}
        </p>
      </div>
    </div>
  );
  if (inline) return content;
  return (
    <Disclosure
      title={
        live
          ? "AI investigation analysis (advisory)"
          : "Rule-based explanation"
      }
    >
      {content}
    </Disclosure>
  );
}

export function PreflightView({
  detail,
  busy,
  onRun,
}: {
  detail: Detail;
  busy: boolean;
  onRun: () => void;
}) {
  const report = detail.simulations[0];
  const currentPassed = detail.checks.filter((check) => check.passed).length;
  const stale =
    report &&
    (report.revision !== detail.submission.revision ||
      report.ruleSet !== detail.ruleSet);
  const execution =
    report &&
    detail.automation?.find((attempt) => attempt.simulation_id === report.id);
  return (
    <Panel
      title="Submission readiness"
      subtitle="Check the current package and compare a permitted correction."
      action={
        <button className="button primary" onClick={onRun} disabled={busy}>
          {busy ? "Checking..." : "Check handover readiness"}
        </button>
      }
    >
      <div className="panel-body">
        {report ? (
          <>
            <div className="readiness-heading">
              <Badge
                value={
                  stale
                    ? "SNAPSHOT_OUTDATED"
                    : report.actualReady
                      ? "READY_TO_ROUTE"
                      : "NOT_READY"
                }
              />
              <span>Checked {dateTime(report.createdAt)} SGT</span>
            </div>
            <div className="readiness-comparison">
              <section>
                <span className="content-label">
                  {stale ? "Before the proposed action" : "Current readiness"}
                </span>
                <strong>
                  {report.checksPassed}
                  <span> / {report.totalChecks} checks passed</span>
                </strong>
                <p>
                  {report.blockingIssues} blocking{" "}
                  {report.blockingIssues === 1 ? "issue" : "issues"}
                </p>
              </section>
              <div className="comparison-arrow" aria-hidden="true">
                &rarr;
              </div>
              <section>
                <span className="content-label">
                  Predicted after attachment
                </span>
                <strong>
                  {report.predictedPassedAfterAttachment}
                  <span>
                    {" "}
                    / {report.predictedTotalChecks ?? report.totalChecks} checks
                    passed
                  </span>
                </strong>
                <p>
                  {report.predictedBlockingIssues} blocking issues would remain
                </p>
                <small>Simulation only</small>
              </section>
            </div>
            <dl className="summary-list">
              <div>
                <dt>Proposed action</dt>
                <dd>
                  {report.permittedAttachmentAvailable
                    ? "Attach authorised supporting document"
                    : "No eligible attachment available"}
                </dd>
              </div>
              <div>
                <dt>Safe and permitted</dt>
                <dd>
                  {report.safeAdministrativeAttachment === undefined
                    ? "Not recorded"
                    : report.safeAdministrativeAttachment
                      ? "Passed for this snapshot"
                      : "Not eligible"}
                </dd>
              </div>
              <div>
                <dt>Human review</dt>
                <dd>
                  {report.humanApprovalRequired
                    ? "Required"
                    : "No extraction review flagged"}
                </dd>
              </div>
              <div>
                <dt>Actual outcome</dt>
                <dd>
                  {execution
                    ? execution.status === "VERIFIED"
                      ? "Attachment executed and verified"
                      : label(execution.status)
                    : "No action executed from this snapshot"}
                </dd>
              </div>
            </dl>
            {stale && (
              <div className="context-note">
                <strong>The package has changed since this snapshot.</strong>
                <p>
                  The current package has {currentPassed}/{detail.checks.length}{" "}
                  checks passed
                  {detail.submission.status === "OPERATIONS"
                    ? " and has been sent to Operations"
                    : ""}
                  . Run preflight again for a current comparison.
                </p>
              </div>
            )}
            <Disclosure title="Validation checks">
              <Checklist checks={report.checks} />
            </Disclosure>
            <Disclosure title="Simulation details">
              <p>{report.explanation}</p>
              <dl className="metadata">
                <div>
                  <dt>Snapshot revision</dt>
                  <dd>{report.revision}</dd>
                </div>
                <div>
                  <dt>Snapshot reference</dt>
                  <dd>{report.id}</dd>
                </div>
                <div>
                  <dt>New issues predicted</dt>
                  <dd>
                    {report.noNewIssues === undefined
                      ? "Not recorded"
                      : report.noNewIssues
                        ? "None"
                        : "Review required"}
                  </dd>
                </div>
                <div>
                  <dt>Current revision validated</dt>
                  <dd>{report.validatedCurrentRevision ? "Yes" : "No"}</dd>
                </div>
              </dl>
            </Disclosure>
          </>
        ) : (
          <Empty
            title="Check this package before handover"
            description="Preflight shows actual readiness and predicts the effect of an eligible supporting-document attachment. It does not execute the action."
          />
        )}
      </div>
    </Panel>
  );
}

export function AutonomyEvidence({ detail }: { detail: Detail }) {
  if (detail.submission.status === "CANCELLED") return null;
  const attempt = [...detail.automation]
    .filter((item) => item.action_type === "ATTACH_VERIFIED_DOCUMENT")
    .sort((a, b) => b.created_at.localeCompare(a.created_at))
    .find((item) => item.revision === detail.submission.revision ||
      (item.status === "VERIFIED" && detail.submission.status === "OPERATIONS" &&
        detail.exceptions.some((issue) => issue.id === item.exception_id && issue.status === "RESOLVED")));
  if (!attempt) return null;
  const simulation = detail.simulations.find((item) => item.id === attempt.simulation_id);
  const verified = attempt.status === "VERIFIED";
  const stages = [
    "Missing document detected",
    ...(simulation?.candidateVersionId || verified ? ["Repository document found"] : []),
    ...(attempt.simulation_id ? ["Digital Twin simulation run"] : []),
    ...(verified ? ["Document attached", "Attachment independently verified"] : []),
    ...(verified && detail.submission.status === "OPERATIONS" ? ["Sent to Operations"] : []),
  ];
  return (
    <section className="case-automation" aria-label="Automatic resolution">
      <h2>Automatic resolution</h2>
      <ol className="case-progress" aria-label="Recorded automatic resolution stages">
        {stages.map((stage) => <li key={stage} data-done="true"><Check size={15} aria-hidden="true" />{stage}</li>)}
      </ol>
      {attempt.simulation_id && <p>The simulation predicted the outcome before any attachment was made.</p>}
      {attempt.status === "BLOCKED" && <p>The safety check blocked the attachment.</p>}
      {attempt.status === "FAILED" && <p>The attachment could not be verified, so the change was rolled back.</p>}
      <Disclosure title="Automatic resolution details">
        <p>{simulation?.explanation || label(attempt.status)}</p>
        <p className="preserve-lines small mono">{detailText(attempt.result_json)}</p>
      </Disclosure>
    </section>
  );
}
export function HandoverHistory({ detail }: { detail: Detail }) {
  return <section className="case-audit" aria-label="Case activity">
    <AuditList items={[...detail.audit].sort((a, b) => b.timestamp.localeCompare(a.timestamp))} />
  </section>;
}
