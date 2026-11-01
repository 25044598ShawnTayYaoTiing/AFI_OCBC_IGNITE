import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import {
  AlertCircle,
  ArrowRight,
  ArrowUpRight,
  Boxes,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  Clock3,
  Database,
  FileCheck2,
  FileSearch,
  FlaskConical,
  GitBranch,
  Info,
  Layers3,
  LockKeyhole,
  LoaderCircle,
  Play,
  RefreshCw,
  RotateCcw,
  ScanLine,
  ShieldCheck,
  ShieldX,
  Sparkles,
  TriangleAlert,
  UserCheck,
  X,
} from "lucide-react";
import {
  dateTime,
  dateKey,
  detailText,
  detailSearchText,
  formatNumber,
  label,
  post,
  publicApi,
  useResource,
} from "../api";
import type {
  Audit,
  Case,
  Investigation,
  Page,
  Resolution,
  Simulation,
} from "../api";
import { Dialog } from "../ui";
import { isReviewer, useAuth } from "../auth";
import {
  readNotificationPreferences,
  readUiPreferences,
  saveNotificationPreferences,
  saveUiPreferences,
} from "../preferences";
import type { NotificationPreferences, UiPreferences } from "../preferences";
import {
  Badge,
  Empty,
  ErrorBox,
  Loading,
  Notice,
  OperationStatus,
  PageHeader,
  Panel,
  SearchBox,
  SelectCase,
  Workflow,
  useOperation,
} from "../components";

function useCaseWorkspace() {
  const [params, setParams] = useSearchParams();
  const selected = params.get("id") || "";
  const cases = useResource<Page<Case>>(
    "/api/exceptions?size=100&sort=detectedAt,desc",
    10000,
  );
  useEffect(() => {
    if (!selected && cases.data?.items.length) {
      const first =
        cases.data.items.find(
          (c) => c.type === "PARTIAL_BATCH_FAILURE" && c.status !== "RESOLVED",
        ) || cases.data.items[0];
      setParams({ id: first.id }, { replace: true });
    }
  }, [selected, cases.data, setParams]);
  const record = useResource<Case>(
    selected ? `/api/exceptions/${selected}` : null,
    5000,
  );
  return { cases, record, selected, select: (id: string) => setParams({ id }) };
}

export function Twin() {
  const { cases, record, selected, select } = useCaseWorkspace();
  const investigation = useResource<Investigation>(
    selected ? `/api/exceptions/${selected}/investigation` : null,
    5000,
  );
  const simulations = useResource<Simulation[]>(
    selected ? `/api/exceptions/${selected}/simulations` : null,
  );
  const op = useOperation();
  const navigate = useNavigate();
  const item = record.data;
  const findings =
    investigation.data?.exceptionId === selected ? investigation.data : null;
  const results = useMemo(() => {
    const latest = new Map<string, Simulation>();
    for (const sim of simulations.data || []) {
      if (
        !latest.has(sim.strategy) ||
        new Date(sim.createdAt) > new Date(latest.get(sim.strategy)!.createdAt)
      )
        latest.set(sim.strategy, sim);
    }
    return [...latest.values()].sort((a, b) =>
      a.strategy === "RETRY_FULL_BATCH"
        ? -1
        : b.strategy === "RETRY_FULL_BATCH"
          ? 1
          : 0,
    );
  }, [simulations.data]);
  const currentStage =
    item?.status === "RESOLVED"
      ? 5
      : op.busy && op.busy !== "simulate"
        ? 3
        : results.length
          ? 3
          : findings
            ? 2
            : item
              ? 1
              : 0;
  async function run() {
    await op.run("simulate", async () => {
      await post(`/api/exceptions/${selected}/simulate`);
      await simulations.refresh();
      await record.refresh();
      op.setMessage(
        "Simulation completed against an isolated snapshot. Review each strategy before proceeding.",
      );
    });
  }
  async function resolve(sim: Simulation) {
    await op.run(sim.id, async () => {
      await post(`/api/exceptions/${selected}/resolve`, {
        actionType: sim.strategy,
        simulationId: sim.id,
        idempotencyKey: `ui-${selected}-${sim.id}`,
      });
      navigate(`/legacy/resolutions?id=${selected}`);
    });
  }
  return (
    <>
      <PageHeader
        eyebrow="TEST THE RECOVERY. PROTECT THE OUTCOME."
        title="Digital Twin Simulator"
        description="Compare the impact of each action before it touches operational records."
      >
        <span className="isolation-pill">
          <LockKeyhole size={14} />
          Isolated simulation
        </span>
      </PageHeader>
      <OperationStatus {...op} />
      {op.busy && (
        <div
          className="twin-execution-progress"
          role="status"
          aria-live="polite"
        >
          <LoaderCircle className="spin" size={20} />
          <div>
            <strong>
              {op.busy === "simulate"
                ? "Evaluating recovery strategies"
                : "Executing recovery and verifying records"}
            </strong>
            <span>
              The backend request is in progress. Its persisted result will
              appear here when complete.
            </span>
          </div>
        </div>
      )}
      <Panel className="selection-panel">
        <SelectCase
          items={cases.data?.items || []}
          selected={selected}
          onChange={select}
        />
        {selected && (
          <Link className="text-link" to={`/legacy/exceptions/${selected}`}>
            View investigation
            <ArrowUpRight size={15} />
          </Link>
        )}
        <button
          className={`button ${results.length ? "secondary" : "primary"}`}
          disabled={
            !findings ||
            !!op.busy ||
            item?.status === "RESOLVED" ||
            item?.status === "INVESTIGATING"
          }
          onClick={() => void run()}
        >
          <Play size={16} />
          {op.busy === "simulate"
            ? "Running simulations…"
            : results.length
              ? "Run new simulations"
              : "Run simulations"}
        </button>
      </Panel>
      <ol className="twin-stepper" aria-label="Recovery workflow progress">
        {[
          "Exception",
          "Investigation",
          "Simulation",
          "Recovery",
          "Verification",
        ].map((stage, index) => (
          <li
            key={stage}
            className={`${index < currentStage ? "complete" : ""} ${index === currentStage ? "current" : ""}`}
            aria-current={index === currentStage ? "step" : undefined}
          >
            <span className="twin-step-number">
              {index < currentStage ? <Check size={13} /> : index + 1}
            </span>
            <span>{stage}</span>
          </li>
        ))}
      </ol>
      {cases.error || record.error ? (
        <ErrorBox
          message={cases.error || record.error}
          retry={() => void Promise.all([cases.refresh(), record.refresh()])}
        />
      ) : cases.loading || record.loading ? (
        <Loading text="Preparing the recovery workspace…" />
      ) : !selected ? (
        <Empty
          title="A case is the starting point"
          description="Run detection to create an exception, then investigate it before simulating recovery."
        >
          <Link to="/legacy/exceptions" className="button primary">
            Open exceptions
            <ArrowRight size={16} />
          </Link>
        </Empty>
      ) : (
        item && (
          <>
            {investigation.error && (
              <ErrorBox
                message={investigation.error}
                retry={() => void investigation.refresh()}
              />
            )}
            {investigation.loading && !findings && (
              <Loading text="Retrieving AI investigation findings…" />
            )}
            {!investigation.loading && !investigation.error && !findings && (
              <Notice>
                Complete the investigation for this case to generate allowlisted
                candidate actions.{" "}
                <Link to={`/legacy/exceptions/${selected}`}>
                  Open investigation →
                </Link>
              </Notice>
            )}
            <div className="twin-source">
              <div>
                <span className="soft-icon">
                  <Database size={23} />
                </span>
                <div>
                  <span className="eyebrow">ORIGINAL OPERATIONAL STATE</span>
                  <h2>{item.batchId || item.id}</h2>
                  <span className="small muted">
                    Read from persistent synthetic records
                  </span>
                </div>
              </div>
              <div className="twin-source-stats">
                <div>
                  <strong>{formatNumber(item.evidence?.totalRecords)}</strong>
                  <span>Total records</span>
                </div>
                <div>
                  <strong className="green-text">
                    {formatNumber(item.evidence?.processedRecords)}
                  </strong>
                  <span>Already processed</span>
                </div>
                <div>
                  <strong className="amber-text">
                    {formatNumber(item.evidence?.pendingRecords)}
                  </strong>
                  <span>Pending recovery</span>
                </div>
              </div>
              <Badge value={item.status} />
            </div>
            {findings && (
              <section
                className="twin-investigation"
                aria-labelledby="twin-investigation-title"
              >
                <div className="twin-investigation-heading">
                  <div className="twin-investigation-title">
                    <span className="twin-investigation-icon">
                      <Sparkles size={17} />
                    </span>
                    <div>
                      <span className="eyebrow">INVESTIGATION FINDINGS</span>
                      <h2 id="twin-investigation-title">
                        What the evidence says
                      </h2>
                    </div>
                  </div>
                  <div className="twin-investigation-meta">
                    <span className="twin-method">
                      {label(findings.method)} analysis
                    </span>
                    {findings.requiresAdditionalEvidence && (
                      <Badge value="REQUIRES_APPROVAL" />
                    )}
                  </div>
                </div>
                <div className="twin-investigation-grid">
                  <div className="twin-root-cause">
                    <span className="twin-section-label">
                      PROBABLE ROOT CAUSE
                    </span>
                    <h3>{findings.probableRootCause}</h3>
                    <p>{findings.explanation}</p>
                  </div>
                  <div className="twin-evidence">
                    <span className="twin-section-label">
                      EVIDENCE REVIEWED
                    </span>
                    {findings.evidence.length ? (
                      <ul>
                        {findings.evidence.map((entry, index) => (
                          <li key={`${index}-${entry}`}>
                            <Check size={14} />
                            <span>{entry}</span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p>No evidence details were returned for this case.</p>
                    )}
                  </div>
                </div>
                {findings.candidateActions.length > 0 && (
                  <div className="twin-candidates">
                    <span className="twin-section-label">
                      CANDIDATE ACTIONS
                    </span>
                    <div>
                      {findings.candidateActions.map((action) => (
                        <span className="twin-candidate" key={action}>
                          {label(action)}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                {findings.fallbackReason && (
                  <p className="twin-fallback-note">
                    Fallback reason: {findings.fallbackReason}
                  </p>
                )}
              </section>
            )}
            <div className="twin-connector">
              <span />
              <GitBranch size={17} />
              <strong>Clone state · Test strategies · Check invariants</strong>
              <span />
            </div>
            {simulations.error ? (
              <ErrorBox
                message={simulations.error}
                retry={() => void simulations.refresh()}
              />
            ) : simulations.loading ? (
              <Loading text="Retrieving simulation results…" />
            ) : !results.length ? (
              <div className="simulation-empty">
                <div className="simulation-preview">
                  <div>
                    <span className="preview-step">STRATEGY 01</span>
                    <RefreshCw size={29} />
                    <h3>Retry entire batch</h3>
                    <p>Assess duplicate-processing risk across every record.</p>
                  </div>
                  <span className="versus">VS</span>
                  <div>
                    <span className="preview-step">STRATEGY 02</span>
                    <ScanLine size={29} />
                    <h3>Resume pending only</h3>
                    <p>
                      Test selective recovery while preserving completed work.
                    </p>
                  </div>
                </div>
                <p>
                  <Info size={15} />
                  Results appear after the simulator evaluates the actual
                  dataset.
                </p>
              </div>
            ) : (
              <div
                className={`strategy-grid ${results.length === 1 ? "single-strategy" : ""}`}
              >
                {results.map((sim, index) => {
                  const eligible = sim.safe && sim.decision === "ELIGIBLE";
                  const manual = sim.decision === "REQUIRES_APPROVAL";
                  const stale =
                    !!item.evidence?.sourceHash &&
                    item.evidence.sourceHash !== sim.sourceHash;
                  return (
                    <article
                      key={sim.id}
                      className={`strategy-card ${eligible ? "eligible" : manual ? "manual" : "rejected"}`}
                    >
                      <div className="strategy-header">
                        <div>
                          <span className="eyebrow">STRATEGY 0{index + 1}</span>
                          <h2>{label(sim.strategy)}</h2>
                        </div>
                        <span className="strategy-icon">
                          {eligible ? (
                            <ShieldCheck size={25} />
                          ) : manual ? (
                            <UserCheck size={25} />
                          ) : (
                            <ShieldX size={25} />
                          )}
                        </span>
                      </div>
                      <div className="strategy-decision">
                        <Badge value={sim.decision} />
                        {eligible && <span>Policy eligible</span>}
                      </div>
                      <p className="strategy-explanation">{sim.explanation}</p>
                      <div className="strategy-metrics">
                        <div>
                          <span>Records affected</span>
                          <strong>{formatNumber(sim.affectedRecords)}</strong>
                        </div>
                        <div>
                          <span>Already completed</span>
                          <strong>{formatNumber(sim.alreadyProcessed)}</strong>
                        </div>
                        <div>
                          <span>Pending recovery</span>
                          <strong>{formatNumber(sim.pendingRecords)}</strong>
                        </div>
                        <div
                          className={
                            sim.duplicateAttemptRisk
                              ? "risk-metric"
                              : "safe-metric"
                          }
                        >
                          <span>Duplicate attempts</span>
                          <strong>
                            {formatNumber(sim.duplicateAttemptRisk)}
                            {sim.duplicateAttemptRisk ? (
                              <TriangleAlert size={17} />
                            ) : (
                              <Check size={17} />
                            )}
                          </strong>
                        </div>
                      </div>
                      <div className="predicted-result">
                        <div>
                          <span>Predicted completed records</span>
                          <strong>
                            {formatNumber(sim.expectedSuccessfulRecords)}
                            <span> / {formatNumber(sim.totalRecords)}</span>
                          </strong>
                        </div>
                        <div className="progress-track">
                          <span
                            style={{
                              width: `${sim.totalRecords ? Math.min(100, (sim.expectedSuccessfulRecords / sim.totalRecords) * 100) : 0}%`,
                            }}
                          />
                        </div>
                        <div className="prediction-note">
                          {eligible
                            ? "Existing completed records are preserved."
                            : "Prediction alone does not authorise execution."}
                        </div>
                      </div>
                      <div className="invariant-result">
                        <h3>
                          {sim.policyViolations.length
                            ? "Safety checks requiring attention"
                            : "Safety checks passed"}
                        </h3>
                        {sim.policyViolations.length ? (
                          sim.policyViolations.map((v, i) => (
                            <p key={i}>
                              <AlertCircle size={15} />
                              {v}
                            </p>
                          ))
                        ) : (
                          <p>
                            <CircleCheck size={15} />
                            No transaction-state violations detected in this
                            snapshot.
                          </p>
                        )}
                      </div>
                      <details className="simulation-details">
                        <summary>
                          Inspect simulation record <ChevronRight size={14} />
                        </summary>
                        <dl>
                          <dt>Simulation ID</dt>
                          <dd>{sim.id}</dd>
                          <dt>Source-state hash</dt>
                          <dd>{sim.sourceHash}</dd>
                          <dt>Evaluated at · SGT</dt>
                          <dd>{dateTime(sim.createdAt)}</dd>
                        </dl>
                      </details>
                      <div className="strategy-action">
                        {stale && (
                          <p className="stale-note">
                            <Clock3 size={14} />
                            Operational state changed. Run a fresh simulation.
                          </p>
                        )}
                        {eligible ? (
                          <button
                            className="button primary wide"
                            disabled={
                              stale || !!op.busy || item.status === "RESOLVED"
                            }
                            onClick={() => void resolve(sim)}
                          >
                            <ShieldCheck size={16} />
                            {op.busy === sim.id
                              ? "Executing & verifying…"
                              : item.status === "RESOLVED"
                                ? "Case already resolved"
                                : "Execute eligible recovery"}
                            <ArrowRight size={16} />
                          </button>
                        ) : manual ? (
                          <Link
                            className="button secondary wide"
                            to={`/legacy/resolutions?id=${selected}`}
                          >
                            <UserCheck size={16} />
                            Open human review
                            <ArrowRight size={16} />
                          </Link>
                        ) : (
                          <div className="blocked-action">
                            <LockKeyhole size={15} />
                            Execution blocked by safety policy
                          </div>
                        )}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
            <div className="twin-principles">
              <div>
                <Layers3 size={19} />
                <strong>Isolated snapshot</strong>
                <span>Simulation never mutates operational records.</span>
              </div>
              <div>
                <ShieldCheck size={19} />
                <strong>Independent policy checks</strong>
                <span>The backend revalidates state before execution.</span>
              </div>
              <div>
                <CheckCheck size={19} />
                <strong>Verified outcomes</strong>
                <span>Resolution requires actual record verification.</span>
              </div>
            </div>
          </>
        )
      )}
    </>
  );
}

export function ResolutionPage() {
  const { cases, record, selected, select } = useCaseWorkspace();
  const actions = useResource<Resolution[]>(
    selected ? `/api/exceptions/${selected}/resolution` : null,
    5000,
  );
  const investigation = useResource<Investigation>(
    selected ? `/api/exceptions/${selected}/investigation` : null,
    5000,
  );
  const op = useOperation();
  const { session, switchUser } = useAuth();
  const [comments, setComments] = useState("");
  const item = record.data;
  const manual =
    investigation.data?.requiresAdditionalEvidence ||
    investigation.data?.candidateActions.includes("REQUEST_MANUAL_REVIEW") ||
    ["AWAITING_APPROVAL", "MANUAL_REVIEW", "APPROVAL_REQUIRED"].includes(
      item?.status || "",
    );
  useEffect(() => {
    setComments("");
  }, [selected]);
  async function approve(decision: string) {
    await op.run(decision, async () => {
      await post(`/api/exceptions/${selected}/approval`, {
        decision,
        actionType: "REQUEST_MANUAL_REVIEW",
        comments,
      });
      op.setMessage(
        decision === "APPROVED"
          ? "Manual handoff acknowledged. The case remains unresolved for further investigation."
          : "Handoff rejected and your reason recorded. The case remains unresolved.",
      );
      setComments("");
      await record.refresh();
      await actions.refresh();
    });
  }
  return (
    <>
      <PageHeader
        eyebrow="CONTROLLED ACTION. VERIFIED RESULTS."
        title="Resolution & Approvals"
        description="Review recovery outcomes and make accountable decisions on uncertain cases."
      />
      <OperationStatus {...op} />
      <Panel className="selection-panel">
        <SelectCase
          items={cases.data?.items || []}
          selected={selected}
          onChange={select}
        />
        {selected && (
          <>
            <Link className="text-link" to={`/legacy/exceptions/${selected}`}>
              Review evidence
              <ArrowUpRight size={15} />
            </Link>
            <Link
              className="button secondary"
              to={`/legacy/audit?id=${selected}`}
            >
              <FileSearch size={15} />
              View audit trail
            </Link>
          </>
        )}
      </Panel>
      {cases.error || record.error || investigation.error ? (
        <ErrorBox
          message={cases.error || record.error || investigation.error}
          retry={() =>
            void Promise.all([
              cases.refresh(),
              record.refresh(),
              investigation.refresh(),
            ])
          }
        />
      ) : record.loading || cases.loading || investigation.loading ? (
        <Loading />
      ) : !item ? (
        <Empty
          title="No case selected"
          description="Detect an exception to begin the recovery workflow."
        />
      ) : (
        <>
          <Panel className="resolution-summary">
            <div className="resolution-summary-heading">
              <span
                className={`resolution-state-icon ${item.status === "RESOLVED" ? "resolved" : ""}`}
              >
                {item.status === "RESOLVED" ? (
                  <ShieldCheck size={27} />
                ) : (
                  <GitBranch size={27} />
                )}
              </span>
              <div>
                <h2>
                  {item.status === "RESOLVED"
                    ? "Recovery verified. Case resolved."
                    : manual
                      ? "Human judgment is required."
                      : "Every recovery starts with a safe strategy."}
                </h2>
                <p>
                  {item.id} · {label(item.type)}
                </p>
              </div>
              <Badge value={item.status} />
            </div>
            <Workflow status={item.status} />
          </Panel>
          {manual && (
            <div className="approval-layout">
              <Panel
                title="Human review"
                subtitle="Review the evidence and record your decision"
                action={<UserCheck size={21} className="amber-text" />}
              >
                <div className="approval-body">
                  <Notice>
                    This approval acknowledges a manual handoff. It does not
                    authorise record changes or bypass safety rules. The case
                    stays unresolved.
                  </Notice>
                  {investigation.data && (
                    <>
                      <h3 className="section-label">Investigation finding</h3>
                      <p>{investigation.data.probableRootCause}</p>
                      <p className="muted">{investigation.data.explanation}</p>
                      <ul className="findings">
                        {investigation.data.evidence.map((e, i) => (
                          <li key={i}>
                            <FileCheck2 size={15} />
                            {e}
                          </li>
                        ))}
                      </ul>
                    </>
                  )}
                  <div className="proposed-action">
                    <span>Proposed action</span>
                    <strong>Request manual review</strong>
                    <Badge value="REQUIRES_APPROVAL" />
                  </div>
                  {isReviewer(session) ? (
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        void approve("APPROVED");
                      }}
                    >
                      <label className="review-label">
                        Decision justification{" "}
                        <span>Required · 10 characters minimum</span>
                        <textarea
                          value={comments}
                          onChange={(e) => setComments(e.target.value)}
                          placeholder="Explain your decision and any next steps for the investigation…"
                          minLength={10}
                          maxLength={2000}
                          required
                          rows={4}
                        />
                      </label>
                      <div className="approval-actions">
                        <button
                          className="button danger-outline"
                          type="button"
                          disabled={!!op.busy || comments.trim().length < 10}
                          onClick={() => void approve("REJECTED")}
                        >
                          <X size={16} />
                          Reject handoff
                        </button>
                        <button
                          className="button primary"
                          disabled={!!op.busy || comments.trim().length < 10}
                        >
                          <Check size={16} />
                          {op.busy
                            ? "Recording decision…"
                            : "Approve manual handoff"}
                        </button>
                      </div>
                      <p className="small muted">
                        Recorded as {session.username}. Reviewer permissions are
                        enforced by the backend.
                      </p>
                    </form>
                  ) : (
                    <div className="reviewer-required">
                      <LockKeyhole size={23} />
                      <div>
                        <h3>A reviewer account is required</h3>
                        <p>
                          Sign in with reviewer credentials to record a
                          decision.
                        </p>
                      </div>
                      <button className="button secondary" onClick={switchUser}>
                        Switch account
                        <ArrowRight size={14} />
                      </button>
                    </div>
                  )}
                </div>
              </Panel>
              <div className="guardrail-note">
                <ShieldCheck size={25} />
                <h3>Approval is a checkpoint.</h3>
                <p>
                  Ambiguous evidence requires a person to investigate. An
                  approval cannot make an unsafe recovery safe.
                </p>
                <Link to={`/legacy/twin?id=${selected}`} className="text-link">
                  Inspect the simulation
                  <ArrowUpRight size={14} />
                </Link>
              </div>
            </div>
          )}
          <Panel
            title="Execution & verification"
            subtitle="Persistent action records and independently checked outcomes"
            action={
              <span className="count-pill">
                {actions.error
                  ? "Unavailable"
                  : actions.loading
                    ? "Loading..."
                    : `${actions.data?.length || 0} actions`}
              </span>
            }
          >
            {actions.error ? (
              <ErrorBox
                message={actions.error}
                retry={() => void actions.refresh()}
              />
            ) : actions.loading ? (
              <Loading />
            ) : !actions.data?.length ? (
              <Empty
                title={
                  manual
                    ? "No automated action has been executed"
                    : "Ready when the safety checks are"
                }
                description={
                  manual
                    ? "The system has retained the case for human review."
                    : "Compare candidate actions in the digital twin, then execute an eligible strategy."
                }
              >
                {!manual && (
                  <Link
                    to={`/legacy/twin?id=${selected}`}
                    className="button primary"
                  >
                    Open digital twin
                    <ArrowRight size={16} />
                  </Link>
                )}
              </Empty>
            ) : (
              <div className="resolution-actions">
                {actions.data.map((action) => (
                  <article className="resolution-record" key={action.id}>
                    <div className="resolution-record-header">
                      <div>
                        <h3>{label(action.actionType)}</h3>
                        <span className="mono small muted">{action.id}</span>
                      </div>
                      <Badge value={action.status} />
                      <span className="small muted">
                        {dateTime(action.executedAt)} SGT
                      </span>
                    </div>
                    {action.verification ? (
                      <>
                        <div
                          className={`verification-banner ${action.verification.passed ? "passed" : "failed"}`}
                        >
                          {action.verification.passed ? (
                            <CircleCheck size={21} />
                          ) : (
                            <TriangleAlert size={21} />
                          )}
                          <div>
                            <strong>
                              {action.verification.passed
                                ? "Independent verification passed"
                                : "Verification did not pass"}
                            </strong>
                            <p>
                              {action.verification.passed
                                ? "Stored record states satisfy the required recovery invariants."
                                : "The case must remain unresolved. Review failed checks before any further action."}
                            </p>
                          </div>
                        </div>
                        <div className="verification-stats">
                          {[
                            {
                              title: "Total expected",
                              value: action.verification.totalRecords,
                            },
                            {
                              title: "Processed",
                              value: action.verification.processedRecords,
                            },
                            {
                              title: "Pending",
                              value: action.verification.pendingRecords,
                            },
                            {
                              title: "Duplicate processing",
                              value:
                                action.verification.duplicateProcessingAttempts,
                            },
                          ].map((v) => (
                            <div key={v.title}>
                              <strong>{formatNumber(v.value)}</strong>
                              <span>{v.title}</span>
                            </div>
                          ))}
                        </div>
                        <div className="verification-checks">
                          {(action.verification.checks || []).map(
                            (check, i) => (
                              <div key={i}>
                                <span
                                  className={
                                    check.passed ? "green-text" : "red-text"
                                  }
                                >
                                  {check.passed ? (
                                    <CircleCheck size={17} />
                                  ) : (
                                    <AlertCircle size={17} />
                                  )}
                                </span>
                                <div>
                                  <strong>{label(check.name)}</strong>
                                  <p>{check.detail}</p>
                                </div>
                                <span
                                  className={`check-result ${check.passed ? "green-text" : "red-text"}`}
                                >
                                  {check.passed ? "Passed" : "Failed"}
                                </span>
                              </div>
                            ),
                          )}
                        </div>
                        <div className="reconciliation-result">
                          <FileCheck2 size={17} />
                          <span>Reconciliation integrity</span>
                          <strong
                            className={
                              action.verification.reconciliationIntegrity
                                ? "green-text"
                                : "red-text"
                            }
                          >
                            {action.verification.reconciliationIntegrity
                              ? "Passed"
                              : "Failed"}
                          </strong>
                        </div>
                      </>
                    ) : (
                      <Notice>
                        No verification result is recorded yet. This action must
                        not be treated as a verified resolution.
                      </Notice>
                    )}
                    <details className="execution-details">
                      <summary>Execution identifiers</summary>
                      <p>
                        Idempotency key: <code>{action.idempotencyKey}</code>
                      </p>
                    </details>
                  </article>
                ))}
              </div>
            )}
          </Panel>
        </>
      )}
    </>
  );
}

export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const selected = params.get("id") || "";
  const cases = useResource<Page<Case>>("/api/exceptions?size=100");
  const audit = useResource<Audit[]>(
    selected ? `/api/exceptions/${selected}/audit` : "/api/audit",
    10000,
  );
  const [query, setQuery] = useState("");
  const [eventType, setEventType] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [page, setPage] = useState(0);
  const pageSize = readUiPreferences().pageSize;
  const events = (audit.data || [])
    .filter(
      (e) =>
        (!eventType || e.eventType === eventType) &&
        (!dateFrom || dateKey(e.timestamp) >= dateFrom) &&
        (!dateTo || dateKey(e.timestamp) <= dateTo) &&
        `${e.eventType} ${e.actor} ${e.exceptionId} ${detailSearchText(e.details)}`
          .toLowerCase()
          .includes(query.toLowerCase()),
    )
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const visibleEvents = events.slice(page * pageSize, (page + 1) * pageSize);
  function exportFiltered() {
    const quote = (value: unknown) =>
      `"${String(value ?? "").replaceAll('"', '""')}"`;
    const rows = [
      [
        "Event ID",
        "Timestamp",
        "Case ID",
        "Event type",
        "Actor or service",
        "Action details",
        "Outcome",
      ],
      ...events.map((event) => [
        event.id,
        event.timestamp,
        event.exceptionId || "",
        label(event.eventType),
        event.actor,
        detailText(event.details),
        label(event.result),
      ]),
    ];
    const csv = rows.map((row) => row.map(quote).join(",")).join("\r\n");
    const url = URL.createObjectURL(
      new Blob([csv], { type: "text/csv;charset=utf-8" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `afi-resolve-audit-${new Date().toISOString().slice(0, 10)}.csv`;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <>
      <PageHeader
        eyebrow="ACCOUNTABILITY AT EVERY STEP"
        title="Audit Trail"
        description="A persistent, chronological record of the decisions behind every recovery."
      >
        <button
          className="button secondary"
          disabled={!events.length || audit.loading}
          onClick={exportFiltered}
        >
          <FileSearch size={15} />
          Export filtered CSV
        </button>
        <button
          className="button secondary"
          onClick={() => void audit.refresh()}
        >
          <RefreshCw size={15} />
          Refresh events
        </button>
      </PageHeader>
      <div className="audit-assurance">
        <ShieldCheck size={18} />
        <span>Append-only through application interfaces</span>
        <span className="dot-separator">·</span>
        <span>Historical events are retained after demo resets</span>
      </div>
      <Panel>
        <div className="table-toolbar audit-toolbar">
          <SearchBox
            value={query}
            onChange={(value) => {
              setQuery(value);
              setPage(0);
            }}
            placeholder="Search events, actors, or case IDs…"
          />
          <select
            aria-label="Filter audit by case"
            value={selected}
            onChange={(e) => (
              setPage(0),
              setParams(e.target.value ? { id: e.target.value } : {})
            )}
          >
            <option value="">All exceptions & system events</option>
            {cases.data?.items.map((c) => (
              <option key={c.id} value={c.id}>
                {c.id} · {label(c.type)}
              </option>
            ))}
            {selected && !cases.data?.items.some((c) => c.id === selected) && (
              <option value={selected}>{selected} · Historical case</option>
            )}
          </select>
          <select
            aria-label="Filter event type"
            value={eventType}
            onChange={(e) => {
              setEventType(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All event types</option>
            {[...new Set((audit.data || []).map((e) => e.eventType))]
              .sort()
              .map((t) => (
                <option key={t} value={t}>
                  {label(t)}
                </option>
              ))}
          </select>
          <label className="date-filter">
            From
            <input
              type="date"
              value={dateFrom}
              onChange={(e) => {
                setDateFrom(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="date-filter">
            To
            <input
              type="date"
              value={dateTo}
              onChange={(e) => {
                setDateTo(e.target.value);
                setPage(0);
              }}
            />
          </label>
        </div>
        <div className="audit-count">
          <span>{events.length} events</span>
          <span>
            <Clock3 size={13} />
            All times in Singapore (SGT)
          </span>
        </div>
        {audit.error ? (
          <ErrorBox message={audit.error} retry={() => void audit.refresh()} />
        ) : audit.loading ? (
          <Loading />
        ) : events.length ? (
          <>
            <div className="table-scroll">
              <table className="audit-table">
                <thead>
                  <tr>
                    <th>Event ID</th>
                    <th>Timestamp</th>
                    <th>Case</th>
                    <th>Event type</th>
                    <th>Actor / service</th>
                    <th>Action</th>
                    <th>Outcome</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleEvents.map((event) => (
                    <tr key={event.id}>
                      <td className="mono small">{event.id}</td>
                      <td className="nowrap">
                        {dateTime(event.timestamp)} SGT
                      </td>
                      <td>
                        {event.exceptionId ? (
                          <Link
                            className="text-link"
                            to={`/legacy/exceptions/${event.exceptionId}`}
                          >
                            {event.exceptionId}
                          </Link>
                        ) : (
                          <span className="muted">System</span>
                        )}
                      </td>
                      <td>
                        <span
                          className={`audit-event-type ${/FAIL|REJECT/.test(event.eventType) ? "negative" : /VERIFIED|RESOLVED|COMPLETED/.test(event.eventType) ? "positive" : ""}`}
                        >
                          {label(event.eventType)}
                        </span>
                      </td>
                      <td>{event.actor}</td>
                      <td>
                        <details className="audit-details">
                          <summary>
                            {detailText(event.details) || "View event details"}
                          </summary>
                          <p>
                            {detailText(event.details) ||
                              "No additional details recorded."}
                          </p>
                          {event.correlationId && (
                            <small>
                              Correlation: <code>{event.correlationId}</code>
                            </small>
                          )}
                        </details>
                      </td>
                      <td>
                        <Badge value={event.result} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pagination audit-pagination">
              <span>
                {events.length
                  ? `${page * pageSize + 1}–${Math.min((page + 1) * pageSize, events.length)} of ${events.length} events`
                  : "0 events"}
              </span>
              <div>
                <button
                  className="button secondary small-button"
                  disabled={page === 0 || audit.loading}
                  onClick={() => setPage(page - 1)}
                >
                  <ChevronLeft size={15} />
                  Previous
                </button>
                <span className="page-number">{page + 1}</span>
                <button
                  className="button secondary small-button"
                  disabled={
                    audit.loading || (page + 1) * pageSize >= events.length
                  }
                  onClick={() => setPage(page + 1)}
                >
                  Next
                  <ChevronRight size={15} />
                </button>
              </div>
            </div>
          </>
        ) : (
          <Empty
            title="No audit events match these filters"
            description="Adjust the date, case, event type, or search terms."
          />
        )}
      </Panel>
    </>
  );
}

export function SettingsPage({ demoOnly = false }: { demoOnly?: boolean }) {
  const { session, switchUser } = useAuth();
  const op = useOperation();
  const [totalRecords, setTotalRecords] = useState(1000);
  const [processedCount, setProcessedCount] = useState(800);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetText, setResetText] = useState("");
  const [preferences, setPreferences] =
    useState<UiPreferences>(readUiPreferences);
  const [notifications, setNotifications] = useState<NotificationPreferences>(
    readNotificationPreferences,
  );
  const [preferencesSaved, setPreferencesSaved] = useState(false);
  const [aiHealth, setAiHealth] = useState<{
    status: string;
    service?: string;
    mode?: string;
  } | null>(null);
  const [aiHealthError, setAiHealthError] = useState("");
  const health = useResource<{ status: string }>("/actuator/health");
  const navigate = useNavigate();
  useEffect(() => {
    let active = true;
    const check = async () => {
      try {
        const result = await publicApi<{
          status: string;
          service?: string;
          mode?: string;
        }>("/ai-health");
        if (active) {
          setAiHealth(result);
          setAiHealthError("");
        }
      } catch (error) {
        if (active) {
          setAiHealth(null);
          setAiHealthError(
            error instanceof Error
              ? error.message
              : "AI service health is unavailable.",
          );
        }
      }
    };
    void check();
    const timer = window.setInterval(() => void check(), 30000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);
  function savePreferences() {
    saveUiPreferences(preferences);
    setPreferencesSaved(true);
    window.setTimeout(() => setPreferencesSaved(false), 2500);
  }
  function updateNotification<K extends keyof NotificationPreferences>(
    key: K,
    value: NotificationPreferences[K],
  ) {
    const next = { ...notifications, [key]: value };
    setNotifications(next);
    saveNotificationPreferences(next);
  }
  async function reset() {
    await op.run("reset", async () => {
      await post("/api/demo/reset");
      setResetOpen(false);
      setResetText("");
      op.setMessage(
        "Synthetic dataset reset. Historical audit events were retained. Trigger a failure to start the primary demonstration.",
      );
    });
  }
  async function trigger() {
    await op.run("trigger", async () => {
      const result = await post<{
        batchId?: string;
        detection?: { detected?: number };
      }>("/api/demo/trigger-failure", { totalRecords, processedCount });
      const detectionCount = result.detection?.detected;
      const detectionMessage =
        detectionCount == null
          ? "Check Exception management for the detected case."
          : `${detectionCount} new exception${detectionCount === 1 ? "" : "s"} detected.`;
      op.setMessage(
        `Partial failure created${result?.batchId ? ` in ${result.batchId}` : ""}. ${detectionMessage} Open Exception management to investigate it.`,
      );
    });
  }
  return (
    <>
      <PageHeader
        eyebrow="WORKSPACE CONFIGURATION"
        title={demoOnly ? "Legacy demo controls" : "Settings"}
        description={
          demoOnly
            ? "Prepare the transaction recovery demonstration. Reviewer permissions apply."
            : "Manage your workspace preferences."
        }
      />
      <OperationStatus {...op} />
      {!demoOnly && (
        <>
          <nav className="settings-nav" aria-label="Settings sections">
            {[
              ["profile", "Profile"],
              ["preferences", "Preferences"],
              ["notifications", "Notifications"],
              ["workspace", "Workspace"],
              ["access", "Users & access"],
              ["integrations", "AI & integrations"],
              ["policies", "Policies"],
              ...(session.demoMode
                ? [["demo-environment", "Demo environment"]]
                : []),
              ["about", "About"],
            ].map(([id, title]) => (
              <a key={id} href={`#${id}`}>
                {title}
              </a>
            ))}
          </nav>
          <div className="settings-grid">
            <Panel
              title="My profile"
              subtitle="Session identity is validated by the backend"
            >
              <div className="settings-details" id="profile">
                <div>
                  <span>Display name</span>
                  <strong className="capitalize">{session.username}</strong>
                </div>
                <div>
                  <span>Role</span>
                  <strong>
                    {isReviewer(session) ? "Reviewer" : "Operations Analyst"}
                  </strong>
                </div>
                <div>
                  <span>Language</span>
                  <strong>English</strong>
                </div>
                <div>
                  <span>Time zone</span>
                  <strong>Asia/Singapore (SGT)</strong>
                </div>
              </div>
              <div className="panel-inner-footer">
                <button className="button secondary" onClick={switchUser}>
                  <UserCheck size={16} />
                  Switch account
                </button>
              </div>
            </Panel>
            <Panel
              title="Safety boundaries"
              subtitle="Built for a synthetic banking operations demonstration"
            >
              <ul className="safety-list">
                {[
                  "Synthetic records only; no customer information.",
                  "No real transfers, settlements, or ledger postings.",
                  "AI recommendations cannot write operational data.",
                  "Execution requires deterministic safety checks.",
                  "Resolution follows independent record verification.",
                ].map((text) => (
                  <li key={text}>
                    <ShieldCheck size={16} />
                    {text}
                  </li>
                ))}
              </ul>
            </Panel>
          </div>
          <div className="settings-grid">
            <Panel
              title="Appearance & preferences"
              subtitle="Saved on this device and restored on refresh"
            >
              <div className="settings-form-grid" id="preferences">
                <label>
                  Theme
                  <select
                    value={preferences.theme}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        theme: event.target.value as UiPreferences["theme"],
                      })
                    }
                  >
                    <option value="light">Light</option>
                    <option value="dark">Dark</option>
                    <option value="system">System</option>
                  </select>
                </label>
                <label>
                  Table density
                  <select
                    value={preferences.density}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        density: event.target.value as UiPreferences["density"],
                      })
                    }
                  >
                    <option value="comfortable">Comfortable</option>
                    <option value="compact">Compact</option>
                  </select>
                </label>
                <label>
                  Date format
                  <select
                    value={preferences.dateFormat}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        dateFormat: event.target
                          .value as UiPreferences["dateFormat"],
                      })
                    }
                  >
                    <option value="d-mmm-yyyy">23 Oct 2026</option>
                    <option value="yyyy-mm-dd">2026-10-23</option>
                  </select>
                </label>
                <label>
                  Time format
                  <select
                    value={preferences.timeFormat}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        timeFormat: event.target
                          .value as UiPreferences["timeFormat"],
                      })
                    }
                  >
                    <option value="24h">24-hour</option>
                    <option value="12h">12-hour</option>
                  </select>
                </label>
                <label>
                  Default table page size
                  <select
                    value={preferences.pageSize}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        pageSize: Number(
                          event.target.value,
                        ) as UiPreferences["pageSize"],
                      })
                    }
                  >
                    <option value={10}>10 rows</option>
                    <option value={20}>20 rows</option>
                    <option value={50}>50 rows</option>
                  </select>
                </label>
                <label>
                  Default dashboard range
                  <select
                    value={preferences.defaultRange}
                    onChange={(event) =>
                      setPreferences({
                        ...preferences,
                        defaultRange: event.target
                          .value as UiPreferences["defaultRange"],
                      })
                    }
                  >
                    <option value="7">Last 7 days</option>
                    <option value="30">Last 30 days</option>
                    <option value="90">Last 90 days</option>
                    <option value="all">All available records</option>
                  </select>
                </label>
              </div>
              <div className="panel-inner-footer">
                <button className="button primary" onClick={savePreferences}>
                  <Check size={15} />
                  {preferencesSaved ? "Preferences saved" : "Save preferences"}
                </button>
                <span className="small muted">
                  Credentials are held in session memory and are not stored with
                  preferences.
                </span>
              </div>
            </Panel>
            <Panel
              title="In-app notifications"
              subtitle="Choose which operational events appear in the notification centre"
            >
              <div className="settings-toggle-list" id="notifications">
                {[
                  [
                    "critical",
                    "Critical exception",
                    "Unresolved critical-severity cases",
                  ],
                  [
                    "approvals",
                    "Approval requests",
                    "Cases with a recorded approval event",
                  ],
                  [
                    "failed",
                    "Failed actions",
                    "Failed or policy-rejected operations",
                  ],
                  [
                    "verification",
                    "Verification failures",
                    "Post-execution verification failures",
                  ],
                ].map(([key, title, description]) => (
                  <label className="settings-toggle" key={key}>
                    <span>
                      <strong>{title}</strong>
                      <small>{description}</small>
                    </span>
                    <input
                      type="checkbox"
                      checked={
                        notifications[key as keyof NotificationPreferences]
                      }
                      onChange={(event) =>
                        updateNotification(
                          key as keyof NotificationPreferences,
                          event.target.checked,
                        )
                      }
                    />
                  </label>
                ))}
              </div>
              <div className="settings-hint" aria-live="polite">
                Document notifications currently use Critical exception for RM
                corrections and Approval requests for human-review cases. Legacy
                failure preferences remain saved. No email or SMS delivery is
                configured.
              </div>
            </Panel>
          </div>
          <div className="settings-grid">
            <Panel
              title="Workspace"
              subtitle="Current operating environment and available modules"
            >
              <div className="settings-details" id="workspace">
                <div>
                  <span>Workspace</span>
                  <strong>AFI Operations</strong>
                </div>
                <div>
                  <span>Environment</span>
                  <Badge
                    value={session.demoMode ? "SYNTHETIC DEMO" : "STANDARD"}
                  />
                </div>
                <div>
                  <span>Application version</span>
                  <strong>2.0</strong>
                </div>
                <div>
                  <span>Operating mode</span>
                  <strong>
                    {session.demoMode
                      ? "Synthetic records only"
                      : "Operational workspace"}
                  </strong>
                </div>
                <div>
                  <span>Modules</span>
                  <strong>
                    Cases · Investigations · Simulation · Approvals · Audit
                  </strong>
                </div>
              </div>
            </Panel>
            <Panel
              title="Users & access"
              subtitle="Roles and actions enforced by the backend"
            >
              <div className="access-roles" id="access">
                <div>
                  <strong>Operations Analyst</strong>
                  <p>
                    Own document submissions, upload synthetic PDFs, validate
                    evidence and execute permitted administrative actions.
                    Legacy recovery is available separately.
                  </p>
                </div>
                <div>
                  <strong>Reviewer</strong>
                  <p>
                    Review submissions across owners and approve rule revisions
                    authored by another reviewer. Legacy demo reset remains
                    reviewer-restricted.
                  </p>
                </div>
                <div>
                  <strong>Administrator</strong>
                  <p>
                    The rules-admin demo reviewer provides independent approval.
                    Account management is configured on the backend.
                  </p>
                </div>
              </div>
              <div className="settings-hint">
                Current session: {session.roles.map(label).join(", ")}. Frontend
                preferences do not change backend permissions.
              </div>
            </Panel>
          </div>
          <div className="settings-grid">
            <Panel
              title="AI & integrations"
              subtitle="Read-only service and model configuration"
            >
              <div className="settings-details" id="integrations">
                <div>
                  <span>Spring Boot API</span>
                  <strong
                    className={
                      health.data?.status === "UP" ? "green-text" : "amber-text"
                    }
                  >
                    {health.loading
                      ? "Checking…"
                      : health.error
                        ? "Unavailable"
                        : label(health.data?.status)}
                  </strong>
                </div>
                <div>
                  <span>FastAPI investigation service</span>
                  <strong
                    className={
                      aiHealth?.status === "UP" ? "green-text" : "amber-text"
                    }
                  >
                    {aiHealth
                      ? label(aiHealth.status)
                      : aiHealthError
                        ? "Unavailable"
                        : "Checking…"}
                  </strong>
                </div>
                <div>
                  <span>Investigation mode</span>
                  <strong>
                    Gemini when configured; deterministic fallback otherwise
                  </strong>
                </div>
                <div>
                  <span>Selected model</span>
                  <strong>Configured in the service environment</strong>
                </div>
                <div>
                  <span>Database connectivity</span>
                  <strong>
                    {health.data?.status === "UP"
                      ? "Included in aggregate backend health"
                      : "Not independently exposed"}
                  </strong>
                </div>
              </div>
              {(health.error || aiHealthError) && (
                <div className="integration-errors">
                  {health.error && (
                    <ErrorBox
                      message={health.error}
                      retry={() => void health.refresh()}
                    />
                  )}
                  {aiHealthError && <ErrorBox message={aiHealthError} />}
                </div>
              )}
              <div className="settings-hint">
                Health checks expose status only. Model keys and sensitive
                configuration are never sent to the browser.
              </div>
            </Panel>
            <Panel
              title="Legacy recovery policies"
              subtitle="Safety rules are controlled by the backend policy engine"
            >
              <div className="policy-list" id="policies">
                <div>
                  <ShieldCheck size={17} />
                  <span>
                    <strong>Legacy: eligible automated recovery</strong>
                    <small>
                      Resume pending records for a verified partial batch
                      failure.
                    </small>
                  </span>
                </div>
                <div>
                  <ShieldX size={17} />
                  <span>
                    <strong>Legacy: restricted recovery action</strong>
                    <small>
                      Retrying a partially processed batch is rejected when it
                      may duplicate processed records.
                    </small>
                  </span>
                </div>
                <div>
                  <LockKeyhole size={17} />
                  <span>
                    <strong>Execution guardrails</strong>
                    <small>
                      Current source-state hash, record invariants, idempotency,
                      and verification are required.
                    </small>
                  </span>
                </div>
                <div>
                  <UserCheck size={17} />
                  <span>
                    <strong>Human review</strong>
                    <small>
                      Reviewer permissions are enforced by the Spring Boot API;
                      approval cannot bypass safety checks.
                    </small>
                  </span>
                </div>
              </div>
            </Panel>
          </div>
        </>
      )}
      {!isReviewer(session) && (
        <Notice>
          Demo reset and failure controls require a reviewer.{" "}
          <button className="text-button" onClick={switchUser}>
            Sign in with reviewer credentials →
          </button>
        </Notice>
      )}
      {session.demoMode && (
        <section id="demo-environment" className="settings-demo-section">
          <div className="section-heading">
            <div>
              <span className="eyebrow">SYNTHETIC ENVIRONMENT</span>
              <h2>Demo environment</h2>
              <p>
                These utilities create or replace synthetic records in the
                shared demonstration workspace.
              </p>
            </div>
            <Badge value="SYNTHETIC DEMO" />
          </div>
          <div className="demo-controls-grid">
            <Panel
              title="Legacy Recovery Demo: trigger a partial batch failure"
              subtitle="Choose a dataset size and the records completed before timeout"
              action={<FlaskConical size={22} className="blue-text" />}
            >
              <form
                className="demo-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void trigger();
                }}
              >
                <div className="form-row">
                  <label>
                    Total synthetic records
                    <input
                      type="number"
                      min={2}
                      max={10000}
                      value={totalRecords}
                      onChange={(e) => setTotalRecords(Number(e.target.value))}
                      required
                    />
                  </label>
                  <label>
                    Processed before timeout
                    <input
                      type="number"
                      min={1}
                      max={totalRecords - 1}
                      value={processedCount}
                      onChange={(e) =>
                        setProcessedCount(Number(e.target.value))
                      }
                      required
                    />
                  </label>
                </div>
                <div className="demo-preview">
                  <div>
                    <strong>{formatNumber(processedCount)}</strong>
                    <span>Processed records</span>
                  </div>
                  <ArrowRight size={18} />
                  <div>
                    <strong>
                      {formatNumber(Math.max(0, totalRecords - processedCount))}
                    </strong>
                    <span>Pending recovery</span>
                  </div>
                  <span className="demo-preview-tag">SCENARIO INPUT</span>
                </div>
                <p className="small muted">
                  These are fixture inputs. Actual simulation outcomes are
                  computed from the persisted records.
                </p>
                <button
                  className="button primary"
                  disabled={
                    !!op.busy ||
                    !isReviewer(session) ||
                    !session.demoMode ||
                    processedCount >= totalRecords ||
                    processedCount < 1 ||
                    totalRecords < 2
                  }
                >
                  <Play size={16} />
                  {op.busy === "trigger"
                    ? "Creating failure…"
                    : "Trigger partial failure"}
                </button>
              </form>
            </Panel>
            <Panel
              title="Reset the synthetic dataset"
              subtitle="Prepare a clean starting point for your next demonstration"
              action={<RotateCcw size={21} className="amber-text" />}
            >
              <div className="reset-body">
                <span className="reset-icon">
                  <Database size={30} />
                </span>
                <p>
                  Reset the current demo records and workflow cases. Historical
                  audit events are kept for traceability.
                </p>
                <p className="small muted">
                  Reset affects this shared demo workspace. Only an authorised
                  reviewer can perform it.
                </p>
                <button
                  className="button danger-outline"
                  disabled={
                    !isReviewer(session) || !session.demoMode || !!op.busy
                  }
                  onClick={() => setResetOpen(true)}
                >
                  <RotateCcw size={16} />
                  Reset demo dataset
                </button>
              </div>
            </Panel>
          </div>
          <Panel
            title="Your presentation, step by step"
            subtitle="A complete exception-to-resolution demonstration"
          >
            <ol className="demo-guide">
              {[
                {
                  title: "Prepare the failure",
                  text: "Reset, then trigger a partially processed synthetic batch.",
                  icon: Database,
                },
                {
                  title: "Detect & investigate",
                  text: "The trigger detects the case. Review it and start an evidence-based investigation.",
                  icon: Sparkles,
                },
                {
                  title: "Compare the strategies",
                  text: "See the full retry rejected and selective recovery evaluated.",
                  icon: Boxes,
                },
                {
                  title: "Resolve & verify",
                  text: "Execute the eligible recovery and inspect stored-record checks.",
                  icon: ShieldCheck,
                },
                {
                  title: "Show the accountability",
                  text: "Walk through every decision and outcome in the audit trail.",
                  icon: FileSearch,
                },
              ].map((step, i) => (
                <li key={step.title}>
                  <span className="guide-number">0{i + 1}</span>
                  <step.icon size={20} />
                  <h3>{step.title}</h3>
                  <p>{step.text}</p>
                </li>
              ))}
            </ol>
            <div className="demo-guide-footer">
              <span>
                For the second scenario, investigate a duplicate or
                reconciliation case and open human review.
              </span>
              <button
                className="button primary"
                onClick={() => navigate("/legacy/exceptions")}
              >
                Open exception workspace
                <ArrowRight size={16} />
              </button>
            </div>
          </Panel>
        </section>
      )}
      {!demoOnly && (
        <div className="settings-about" id="about">
          <div>
            <span className="eyebrow">ABOUT AFI RESOLVE</span>
            <h2>Intelligence before action</h2>
            <p>
              AFI Resolve validates synthetic document packages, reconciles
              evidence, coordinates corrections and routes eligible submissions
              to Operations. The original transaction recovery workflow remains
              available in the Legacy Recovery Demo.
            </p>
          </div>
          <div className="about-meta">
            <strong>AFI Resolve · v1.0</strong>
            <span>Team AFI</span>
            <span>OCBC Ignite Challenge 2026</span>
            <span>
              Independent synthetic demonstration · Not connected to OCBC
              banking systems
            </span>
          </div>
        </div>
      )}
      {resetOpen && (
        <Dialog
          className="modal reset-modal"
          labelledBy="reset-title"
          initialFocus="input"
          onClose={() => setResetOpen(false)}
        >
          <button
            className="icon-button modal-close"
            onClick={() => setResetOpen(false)}
            aria-label="Close reset confirmation"
          >
            <X size={20} />
          </button>
          <span className="reset-icon">
            <RotateCcw size={28} />
          </span>
          <h2 id="reset-title">Reset this demo workspace?</h2>
          <p>
            Current synthetic records and cases will be replaced. Historical
            audit events remain available.
          </p>
          <label>
            Type RESET to confirm
            <input
              value={resetText}
              onChange={(e) => setResetText(e.target.value)}
              placeholder="RESET"
            />
          </label>
          {op.error && <ErrorBox message={op.error} />}
          <div className="modal-actions">
            <button
              className="button secondary"
              onClick={() => setResetOpen(false)}
            >
              Cancel
            </button>
            <button
              className="button danger"
              disabled={resetText !== "RESET" || !!op.busy}
              onClick={() => void reset()}
            >
              {op.busy ? "Resetting…" : "Reset dataset"}
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}
