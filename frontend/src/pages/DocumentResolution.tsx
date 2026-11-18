import { dateTime, detailText, label } from "../api";
import type {
  AutomationAttempt,
  Detail,
  Finding,
  Field,
  Investigation,
  Recommendation,
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

export function AutonomyEvidence({
  detail,
  onInspect,
}: {
  detail: Detail;
  onInspect?: (field: Field) => void;
}) {
  const investigation = detail.investigations?.[0];
  const open = detail.exceptions.filter((item) => item.status === "OPEN");
  const attempts = detail.automation || [];
  const validated =
    detail.submission.validated_revision === detail.submission.revision &&
    detail.checks.length > 0;
  const timeline = detail.audit
    .filter((event) =>
      /EXCEPTION_DETECTED|AI_INVESTIGATION|PREFLIGHT_PASSED|POLICY_APPROVED|ACTION_EXECUTED|OUTCOME_VERIFIED|EXCEPTION_RESOLVED|AUTOMATION_BLOCKED|AUTOMATION_FAILED|ROUTED/.test(
        event.eventType,
      ),
    )
    .slice()
    .reverse();
  return (
    <div className="page-stack">
      <div className="automation-status-strip">
        <div className="automation-status-summary">
          <strong>
            {open.length
              ? `${open.length} ${open.length === 1 ? "issue requires" : "issues require"} attention`
              : validated
                ? "All intake requirements satisfied"
                : "Package requires validation"}
          </strong>
          <p>
            {open.length
              ? "Customer declarations, conflicting details and uncertain evidence require a corrected document or human review."
              : validated
                ? "Verified checks have passed for this revision."
                : "Recheck the package to confirm its current requirements."}
          </p>
        </div>
        <div className="automation-status-badges">
          <Badge
            value={
              detail.automationEnabled ? "POLICY_CONTROLLED" : "MANUAL_REVIEW"
            }
            text={
              detail.automationEnabled
                ? "Auto-attachment enabled"
                : "Automation disabled"
            }
          />
          {investigation && <Badge value={investigation.method} />}
        </div>
      </div>

      {attempts.length > 0 && (
        <Panel
          title="Automated attachment outcomes"
          subtitle="Policy-controlled supporting document retrieval from the authorised case repository."
        >
          <div className="panel-body">
            <div className="automation-results">
              {attempts.map((attempt) => (
                <AutomationResult
                  key={attempt.id}
                  attempt={attempt}
                  detail={detail}
                />
              ))}
            </div>
          </div>
        </Panel>
      )}

      <Panel
        title="AI investigation findings"
        subtitle={
          investigation
            ? `${investigation.model || "Rule-based analysis"} · Revision ${investigation.revision} (Advisory — backend policy controls decisions)`
            : "Automated root-cause and evidence analysis for detected exceptions."
        }
        action={investigation && <Badge value={investigation.method} />}
      >
        <div className="panel-body">
          {investigation ? (
            <div className="investigation-cards-list">
              {investigation.recommendations.map((recommendation) => (
                <RecommendationView
                  key={recommendation.exception_id}
                  recommendation={recommendation}
                  onInspect={onInspect}
                  investigation={investigation}
                  detail={detail}
                />
              ))}
              {investigation.fallback_reason && (
                <Disclosure title="Advanced fallback details">
                  <p className="muted small">
                    Fallback reason: {label(investigation.fallback_reason)}
                  </p>
                </Disclosure>
              )}
            </div>
          ) : (
            <p className="muted">
              No investigation recorded for this package. Investigations run
              automatically when validation detects an exception.
            </p>
          )}
        </div>
      </Panel>
      <Panel
        title="Resolution timeline"
        subtitle="Chronological progression of detection, investigation, verification and routing."
      >
        <AuditList items={timeline} />
      </Panel>
    </div>
  );
}

function AutomationResult({
  attempt,
  detail,
}: {
  attempt: AutomationAttempt;
  detail: Detail;
}) {
  const finding = detail.exceptions.find(
    (item) => item.id === attempt.exception_id,
  );
  const verified = attempt.status === "VERIFIED";
  return (
    <article className="automation-result task-card">
      <div className="task-heading">
        <div>
          <span className="task-category-tag">
            {label(attempt.action_type)}
          </span>
          <h3>{finding?.name || "Missing supporting document"}</h3>
        </div>
        <Badge value={attempt.status} />
      </div>
      <p>
        {verified
          ? "The authorised supporting document was attached from the repository and independently verified."
          : attempt.status === "BLOCKED"
            ? "The action was blocked by preflight policy checks. No automatic change was made."
            : "The attempt did not pass verification and was safely rolled back."}
      </p>
      <div className="task-meta">
        <div className="task-meta-item">
          <span className="task-meta-label">Recorded</span>
          <strong>
            {dateTime(attempt.completed_at || attempt.created_at)} SGT
          </strong>
        </div>
        <div className="task-meta-item">
          <span className="task-meta-label">Revision</span>
          <strong>Revision {attempt.revision}</strong>
        </div>
      </div>
      <Disclosure title="Advanced details">
        <p className="small mono">{detailText(attempt.result_json)}</p>
        <dl className="metadata">
          <div>
            <dt>Attempt ID</dt>
            <dd className="mono">{attempt.id}</dd>
          </div>
          <div>
            <dt>Preflight ID</dt>
            <dd className="mono">{attempt.simulation_id || "Not run"}</dd>
          </div>
        </dl>
      </Disclosure>
    </article>
  );
}

function RecommendationView({
  recommendation,
  investigation,
  detail,
  onInspect,
}: {
  recommendation: Recommendation;
  investigation: Investigation;
  detail: Detail;
  onInspect?: (field: Field) => void;
}) {
  const finding = detail.exceptions.find(
    (item) => item.id === recommendation.exception_id,
  );
  return (
    <article className="investigation-card">
      <div className="investigation-card-header">
        <h3>{finding?.name || label(recommendation.recommended_action)}</h3>
        {finding && <Badge value={finding.status} />}
      </div>
      {finding ? (
        <InvestigationExplanation
          investigation={investigation}
          finding={finding}
          detail={detail}
          onInspect={onInspect}
          inline
        />
      ) : (
        <p className="context-note">
          This investigation refers to an earlier finding from revision{" "}
          {investigation.revision}.
        </p>
      )}
    </article>
  );
}

export function HandoverHistory({ detail }: { detail: Detail }) {
  return <Panel title="Case activity"><AuditList items={detail.audit} /></Panel>;
}
