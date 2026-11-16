import { CheckCircle2 } from "lucide-react";
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
      action={
        detail.submission.status !== "OPERATIONS" && (
          <button className="button secondary" onClick={onUpload}>
            Replace a document
          </button>
        )
      }
    >
      {detail.tasks.length ? (
        <div className="task-list">
          {detail.tasks.map((task) => (
            <article key={task.id}>
              <div className="task-heading">
                <h3>{task.description}</h3>
                <Badge value={task.status} />
              </div>
              {detail.exceptions.find((item) => item.id === task.exception_id) && (
                <p className="content-label">{issueCategory(detail.exceptions.find((item) => item.id === task.exception_id)!)}</p>
              )}
              <p>{task.corrective_action}</p>
              <div className="task-meta">
                <span>Assigned to {label(task.assigned_role)}</span>
                <span>
                  {task.resolved_at
                    ? `Completed ${dateTime(task.resolved_at)}`
                    : `Created ${dateTime(task.created_at)}`}
                </span>
              </div>
              <Disclosure title="Task details">
                <dl className="metadata">
                  <div>
                    <dt>Reference</dt>
                    <dd>{task.id}</dd>
                  </div>
                  <div>
                    <dt>Priority</dt>
                    <dd>{label(task.priority)}</dd>
                  </div>
                </dl>
              </Disclosure>
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title="No corrections requested"
          description="Tasks appear when a form needs customer or reviewer input."
        />
      )}
    </Panel>
  );
}

export function InvestigationExplanation({ investigation, finding, detail, onInspect }: {
  investigation?: Investigation; finding: Finding; detail?: Detail; onInspect?: (field: Field) => void;
}) {
  const recommendation = investigation?.recommendations.find(item => item.exception_id === finding.id);
  const source = investigation?.source_findings?.find(item => item.exception_id === finding.id);
  if (!investigation || !recommendation) return <p className="context-note">No investigation is recorded for this finding. The policy result above remains authoritative.</p>;
  const stale = !!detail && (investigation.revision !== detail.submission.revision || (investigation.rule_set != null && investigation.rule_set !== detail.ruleSet));
  const historical = stale || finding.status === "RESOLVED";
  const live = investigation.method === "GEMINI";
  const cited = (source?.evidence || []).filter(e => recommendation.evidence_ids.includes(e.evidence_id));
  const fields = [...new Set(cited.map(e => e.field))];
  const comparisons = fields.map(field => ({ field, rows: cited.filter(e => e.field === field) })).filter(group => new Set(group.rows.map(row => row.document_id).filter(Boolean)).size > 1);
  return <Disclosure title={live ? "Gemini investigation | advisory" : "Rule-based explanation | AI unavailable"}>
    <div className="investigation-report">
      <div className="provenance-line"><Badge value={investigation.method} /><span>{live ? investigation.model || "Gemini" : "No model-generated analysis"} | Revision {investigation.revision}</span></div>
      {historical && <p className="context-note"><strong>Historical investigation.</strong> {stale ? "The package or rules changed. This explanation must not be used as current approval." : "This issue has since been resolved; the recommendation describes its earlier state."}</p>}
      {detail && !investigation.rule_set && <p className="context-note">The rule-set snapshot was not recorded for this older investigation. Verify against the current policy checks.</p>}
      <section><h4>Investigation result</h4><p>{recommendation.explanation}</p></section>
      <section><h4>Evidence supplied to the investigation</h4>{cited.length ? <div className="investigation-citations">{cited.map((e, index) => {
        const doc = detail?.documents.find(doc => doc.id === e.document_id && doc.versions.some(v => v.id === e.version_id));
        return <article key={e.evidence_id}><strong>{label(e.field)}: {e.value || "Not confirmed"}</strong><p>{e.supporting_text || "No supporting text recorded."}</p><small>{doc?.filename || "Source record"} | {e.page ? 'page ' + e.page : "Page not recorded"}</small>{doc && onInspect && <button className="text-link" onClick={() => onInspect({ document_id: doc.id, version_id: e.version_id || doc.version_id, filename: doc.filename, field_name: e.field, field_value: e.value, page_number: e.page, supporting_text: e.supporting_text })}>View cited source {index + 1}</button>}</article>;
      })}</div> : <p>No source citations were supplied. This does not establish that a document is missing.</p>}</section>
      <section><h4>Cross-document comparison</h4>{comparisons.length ? comparisons.map(group => <div key={group.field}><strong>{label(group.field)}</strong><p>Recorded source values, without selecting a correct value:</p><ul>{group.rows.map(row => <li key={row.evidence_id}>{detail?.documents.find(doc => doc.id === row.document_id)?.filename || "Source document"}: <strong>{row.value || "Not confirmed"}</strong>{row.page ? ' (page ' + row.page + ')' : ''}</li>)}</ul></div>) : <p>No comparable field is cited across multiple documents for this finding. No cross-document conclusion is available.</p>}</section>
      <section><h4>Required next step | policy</h4><p>{finding.status === "RESOLVED" ? "No further correction for this resolved finding. Check the current case status." : finding.corrective_action}</p><small>{recommendation.human_review_required ? "Human review was required by the recorded policy input." : "Any attachment still requires backend authorisation and independent verification."}</small></section>
      <section className="context-note"><h4>Limitations</h4><p>{live ? "Gemini explains the supplied findings and citations. It does not independently approve the application or establish facts beyond those sources." : "A deterministic explanation was used; no live AI investigation was completed."} Spring Boot rules control validation, allowed actions and handover.</p>{investigation.fallback_reason && <small>Availability: {label(investigation.fallback_reason)}</small>}</section>
    </div>
  </Disclosure>;
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

export function AutonomyEvidence({ detail, onInspect }: { detail: Detail; onInspect?: (field: Field) => void }) {
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
      <Panel
        title="Automatic resolution"
        subtitle={
          detail.automationEnabled
            ? "Eligible administrative corrections run automatically under approved rules."
            : "Automatic resolution is disabled in this environment."
        }
      >
        <div className="panel-body">
          {attempts.length ? (
            <div className="automation-results">
              {attempts.map((attempt) => (
                <AutomationResult
                  key={attempt.id}
                  attempt={attempt}
                  detail={detail}
                />
              ))}
            </div>
          ) : (
            <p className="muted">
              No automatic actions have been attempted for this package.
            </p>
          )}
          <div className="context-note">
            <strong>
              {open.length
                ? `${open.length} ${open.length === 1 ? "issue still needs" : "issues still need"} attention`
                : validated
                  ? "No remaining correction tasks"
                  : "Current package needs validation"}
            </strong>
            <p>
              {open.length
                ? "Customer declarations, conflicting details and uncertain evidence require a corrected document or human review."
                : validated
                  ? "The recorded intake requirements have been satisfied."
                  : "Recheck the package to confirm its current requirements and next steps."}
            </p>
          </div>
        </div>
      </Panel>
      <Panel
        title="Investigation evidence"
        action={investigation && <Badge value={investigation.method} />}
      >
        <div className="panel-body">
          {investigation ? (
            <>
              <p className="muted">
                {investigation.model || "Approved rule analysis"} / source
                revision {investigation.revision}
              </p>
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
                <Disclosure title="Fallback details">
                  <p>{label(investigation.fallback_reason)}</p>
                </Disclosure>
              )}
            </>
          ) : (
            <Empty
              title="No investigation recorded"
              description="An investigation starts when validation detects an issue."
            />
          )}
        </div>
      </Panel>
      <Panel
        title="Resolution timeline"
        subtitle="Only recorded stages are shown."
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
    <article className="automation-result">
      <div className="task-heading">
        <h3>{finding?.name || "Missing supporting document"}</h3>
        <Badge value={attempt.status} />
      </div>
      <p>
        {verified
          ? "The authorised supporting document was attached and independently verified."
          : attempt.status === "BLOCKED"
            ? "The action was not eligible. No automatic correction was completed."
            : "The attempt did not complete verification. Review the recorded result."}
      </p>
      <small>{dateTime(attempt.completed_at || attempt.created_at)} SGT</small>
      <Disclosure title="Execution details">
        <p>{detailText(attempt.result_json)}</p>
        <dl className="metadata">
          <div>
            <dt>Action</dt>
            <dd>{label(attempt.action_type)}</dd>
          </div>
          <div>
            <dt>Attempt</dt>
            <dd>{attempt.id}</dd>
          </div>
          <div>
            <dt>Preflight</dt>
            <dd>{attempt.simulation_id || "Not run"}</dd>
          </div>
          <div>
            <dt>Source revision</dt>
            <dd>{attempt.revision}</dd>
          </div>
        </dl>
      </Disclosure>
    </article>
  );
}

function RecommendationView({ recommendation, investigation, detail, onInspect }: {
  recommendation: Recommendation; investigation: Investigation; detail: Detail; onInspect?: (field: Field) => void;
}) {
  const finding = detail.exceptions.find(item => item.id === recommendation.exception_id);
  return <article className="investigation-item"><h3>{finding?.name || label(recommendation.recommended_action)}</h3>{finding ? <InvestigationExplanation investigation={investigation} finding={finding} detail={detail} onInspect={onInspect} /> : <p className="context-note">This investigation refers to an earlier finding. Its current outcome is not available in this view.</p>}</article>;
}

export function HandoverHistory({ detail }: { detail: Detail }) {
  return (
    <>
      <Panel title="Operations handover">
        <div className="panel-body">
          {detail.routing.length ? (
            detail.routing.map((route, index) => (
              <div className="handover-record" key={index}>
                <CheckCircle2 size={20} />
                <div>
                  <strong>Sent to Operations</strong>
                  <p>{dateTime(route.created_at)} SGT</p>
                  <Disclosure title="Handover details">
                    <p>{route.reason}</p>
                  </Disclosure>
                </div>
              </div>
            ))
          ) : (
            <p className="muted">
              This package has not been sent to Operations yet.
            </p>
          )}
        </div>
      </Panel>
      <Panel title="Activity history">
        <AuditList items={detail.audit} />
      </Panel>
    </>
  );
}
