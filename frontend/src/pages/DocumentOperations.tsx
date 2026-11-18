import { nextCaseAction } from "./case-facts";
import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  Download,
  FileText,
} from "lucide-react";
import { detailText, label, post, privatePdf, useResource } from "../api";
import type { Audit } from "../api";
import type { Detail, Metrics, Submission } from "../document-types";
import {
  Badge,
  Empty,
  ErrorBox,
  Loading,
  PageHeader,
  Panel,
  ResourceState,
} from "../components";
import { useAuth } from "../auth";
import { readUiPreferences } from "../preferences";
import { Disclosure, MetricStrip, SectionNav } from "../ui";
import {
  AuditList,
  BASE,
  HorizontalBarChart,
  message,
  reportNav,
  StatusChart,
  submissionNav,
  SubmissionTable,
} from "./DocumentShared";

export function DocumentOverview() {
  const submissions = useResource<Submission[]>(`${BASE}/submissions`, 15000);
  const metrics = useResource<Metrics>(`${BASE}/analytics`, 15000);
  const activity = useResource<Audit[]>(`${BASE}/audit`, 15000);
  const items = submissions.data || [];
  const m = metrics.data;
  const attention = items
    .filter((item) =>
      [
        "CORRECTION_REQUIRED",
        "HUMAN_REVIEW",
        "RESOLUTION_MANAGEMENT",
        "ON_HOLD",
      ].includes(item.status),
    )
    .slice()
    .sort(
      (a, b) =>
        Number(!!b.risk_hold) - Number(!!a.risk_hold) ||
        (b.risk_score ?? -1) - (a.risk_score ?? -1),
    );
  const readyForApproval = attention.filter(
    (item) =>
      item.status === "RESOLUTION_MANAGEMENT" &&
      !item.risk_hold &&
      (item.open_exceptions ?? 0) === 0 &&
      item.validated_revision === item.revision,
  );
  const value = (number: number | undefined) =>
    metrics.loading || metrics.error ? "--" : (number ?? "--");
  return (
    <div className="page-stack command-home">
      <section className="command-masthead">
        <div className="command-masthead-copy">
          <span className="eyebrow">
            Resolution Management · Service Operations
          </span>
          <h1>Document Exception &amp; Handover Command Centre</h1>
          <p>
            Review cross-document evidence, track customer corrections and
            approve verified packages for Operations handover.
          </p>
          <div className="masthead-quick-links">
            <Link className="button primary" to="/submissions?view=ready">
              Ready for approval ({readyForApproval.length})
              <ArrowRight size={15} />
            </Link>
            <Link className="button secondary" to="/submissions?view=corrections">
              Customer corrections
            </Link>
            <Link className="button secondary" to="/operations">
              Completed handovers
            </Link>
          </div>
        </div>
        <Link className="command-focus" to="/exceptions">
          <span>Active intervention queue</span>
          <strong>
            {submissions.loading || submissions.error ? "—" : attention.length}
          </strong>
          <small className="command-focus-sub">
            {readyForApproval.length
              ? `${readyForApproval.length} ready for Operations approval`
              : "Review open findings & corrections"}
          </small>
          <span>
            Open priority queue
            <ArrowRight size={18} />
          </span>
        </Link>
      </section>

      {metrics.error && (
        <ErrorBox
          message={metrics.error}
          retry={() => void metrics.refresh()}
        />
      )}

      <MetricStrip
        items={[
          {
            label: "Requires attention",
            value:
              submissions.loading || submissions.error
                ? "--"
                : attention.length,
            note: `${readyForApproval.length} ready to approve · ${m?.onHold ?? 0} on hold`,
            tone: attention.length > 0 ? "warning" : "default",
          },
          {
            label: "Automatically resolved",
            value: value(m?.automaticallyResolved),
            note: "Verified repository attachments",
            tone: "success",
          },
          {
            label: "Handed to Operations",
            value: value(m?.successfullyRouted),
            note: "Validated & approved packages",
            tone: "accent",
          },
          {
            label: "Total submissions",
            value: value(m?.totalSubmissions),
            note: `${m ? m.statuses.PENDING_VALIDATION || 0 : 0} awaiting validation`,
            tone: "default",
          },
        ]}
      />

      <div className="risk-counts" aria-label="Case risk distribution">
        <span className="risk-counts-title">Risk &amp; hold posture</span>
        {(["HIGH", "MEDIUM", "LOW", "UNASSESSED"] as const).map((level) => (
          <Link
            key={level}
            to="/submissions"
            className={`risk-pill risk-pill-${level.toLowerCase()}`}
          >
            <span>{label(level)} risk</span>
            <strong>
              {metrics.loading || metrics.error
                ? "--"
                : m?.riskCounts
                  ? (m.riskCounts[level] ?? 0)
                  : "--"}
            </strong>
          </Link>
        ))}
        <Link to="/exceptions" className="risk-pill risk-pill-hold">
          <span>On hold</span>
          <strong>
            {metrics.loading || metrics.error ? "--" : (m?.onHold ?? 0)}
          </strong>
        </Link>
      </div>

      <div className="content-columns command-primary-grid">
        <Panel
          title="Priority cases requiring action"
          subtitle="Ordered by hold status and exception risk score."
          action={
            <Link className="text-link" to="/exceptions">
              View all ({attention.length}) <ArrowRight size={14} />
            </Link>
          }
        >
          <ResourceState
            error={submissions.error}
            loading={submissions.loading}
            retry={() => void submissions.refresh()}
          >
            {attention.length ? (
              <div className="record-list">
                {attention.slice(0, 5).map((item) => (
                  <Link
                    key={item.id}
                    className="record-row priority-case-row"
                    to={`/submissions/${item.id}?tab=${(item.open_exceptions ?? 0) > 0 ? "exceptions" : "overview"}`}
                  >
                    <div className="priority-case-main">
                      <div className="priority-case-header">
                        <strong>{item.customer_name}</strong>
                        <span className="reference">
                          {item.customer_reference}
                        </span>
                        <span className="priority-service-tag">
                          {label(item.category)}
                        </span>
                      </div>
                      <p className="queue-next-action">
                        <span className="next-action-indicator">Next:</span>{" "}
                        {nextCaseAction(item, item.open_exceptions ?? 0)}
                      </p>
                      <small className="priority-case-meta">
                        {item.open_exceptions
                          ? `${item.open_exceptions} open ${item.open_exceptions === 1 ? "finding" : "findings"}`
                          : "All checks passed — awaiting RM approval"}{" "}
                        · Revision {item.revision}
                      </small>
                    </div>
                    <div className="priority-case-badges">
                      {item.risk_hold && (
                        <Badge value="ON_HOLD" text="On hold" />
                      )}
                      {item.risk_level && (
                        <Badge
                          value={item.risk_level}
                          text={`${label(item.risk_level)} · ${item.risk_score ?? 0}`}
                        />
                      )}
                      <Badge value={item.status} />
                    </div>
                    <ArrowRight size={16} />
                  </Link>
                ))}
              </div>
            ) : (
              <Empty
                title="You're up to date"
                description="No cases currently need correction or human review."
              />
            )}
          </ResourceState>
        </Panel>
        <Panel
          title="Automation &amp; pipeline outcomes"
          subtitle="Live metrics from policy validation and autonomous resolution."
          action={
            <Link className="text-link" to="/reports">
              Analytics &amp; reports <ArrowRight size={14} />
            </Link>
          }
        >
          <div className="panel-body">
            <div className="automation-kpi-grid">
              <div className="automation-kpi-card tone-success">
                <span>Auto-resolved</span>
                <strong>{value(m?.automaticallyResolved)}</strong>
                <small>Verified attachments</small>
              </div>
              <div className="automation-kpi-card tone-warning">
                <span>Human review</span>
                <strong>{value(m?.humanInterventionRequired)}</strong>
                <small>Corrections &amp; RM review</small>
              </div>
              <div className="automation-kpi-card tone-danger">
                <span>Policy blocked</span>
                <strong>{value(m?.failedOrBlockedAutomation)}</strong>
                <small>Unsafe actions prevented</small>
              </div>
            </div>
            {!metrics.error && m && m.totalSubmissions > 0 && (
              <StatusChart metrics={m} />
            )}
          </div>
        </Panel>
      </div>
      <Panel
        title="Recent submissions"
        subtitle="Latest customer requests and document packages across channels."
        action={
          <Link className="text-link" to="/submissions">
            All submissions <ArrowRight size={14} />
          </Link>
        }
      >
        <ResourceState
          error={submissions.error}
          loading={submissions.loading}
          retry={() => void submissions.refresh()}
        >
          <SubmissionTable items={items.slice(0, 6)} />
        </ResourceState>
      </Panel>
      <Panel
        title="Recent audit activity"
        action={
          <Link className="text-link" to="/audit">
            Full audit log <ArrowRight size={14} />
          </Link>
        }
      >
        {activity.error ? (
          <ErrorBox message={activity.error} />
        ) : activity.loading ? (
          <Loading />
        ) : (
          <AuditList items={(activity.data || []).slice(0, 5)} compact />
        )}
      </Panel>
      <p className="page-note">
        Synthetic workspace. <Link to="/demo">Open the demo environment</Link>{" "}
        for sample forms and a guided walkthrough.
      </p>
    </div>
  );
}

export function DocumentQueue({ mode = "submissions" }: { mode?: string }) {
  const [viewParams] = useSearchParams();
  const view = mode === "submissions" ? viewParams.get("view") : null;
  const { session } = useAuth();
  const result = useResource<Submission[]>(`${BASE}/submissions`, 15000);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [source, setSource] = useState("all");
  const [requestType, setRequestType] = useState("all");
  const [sortOrder, setSortOrder] = useState("risk");
  const [riskLevel, setRiskLevel] = useState("all");
  const [validationOutcome, setValidationOutcome] = useState("all");
  const [page, setPage] = useState(0);
  const pageSize = readUiPreferences().pageSize;
  const titles: Record<string, string> = {
    submissions: "Review queue",
    investigations: "Investigations",
    exceptions: "Resolution Management",
    corrections: "Corrections",
    operations: "Operations",
    preflight: "Handover readiness",
  };
  const descriptions: Record<string, string> = {
    submissions: "Create, review and track customer document packages.",
    investigations: "Review AI findings and the evidence behind each decision.",
    exceptions: "Cases that need a correction or human review.",
    corrections: "Track requested corrections and replacement documents.",
    operations: "Packages with a verified Operations handover.",
    preflight: "Check readiness before handing a package to Operations.",
  };
  const items = (result.data || [])
    .filter((item) =>
      view === "assigned"
        ? item.owner_name === session.username
        : view === "corrections"
          ? item.status === "CORRECTION_REQUIRED"
          : view === "ready"
            ? ["RESOLUTION_MANAGEMENT", "READY"].includes(item.status) &&
              item.validated_revision === item.revision &&
              !item.open_exceptions &&
              !item.risk_hold
            : true,
    )
    .filter((item) =>
      mode === "exceptions" || mode === "corrections"
        ? [
            "CORRECTION_REQUIRED",
            "HUMAN_REVIEW",
            "RESOLUTION_MANAGEMENT",
            "ON_HOLD",
          ].includes(item.status)
        : mode === "operations"
          ? item.status === "OPERATIONS"
          : true,
    )
    .filter(
      (item) =>
        (riskLevel === "all" || item.risk_level === riskLevel) &&
        (validationOutcome === "all" ||
          (validationOutcome === "current"
            ? item.validated_revision === item.revision
            : item.validated_revision !== item.revision)) &&
        (requestType === "all" || item.category === requestType) &&
        (status === "all" || item.status === status) &&
        (source === "all" || (item.submission_source || "RM") === source) &&
        `${item.customer_name} ${item.customer_reference} ${item.id}`
          .toLowerCase()
          .includes(search.toLowerCase()),
    );
  items.sort((a, b) =>
    sortOrder === "risk"
      ? Number(!!b.risk_hold) - Number(!!a.risk_hold) ||
        (b.risk_score ?? -1) - (a.risk_score ?? -1)
      : sortOrder === "customer"
        ? a.customer_name.localeCompare(b.customer_name)
        : (Date.parse(b.created_at) - Date.parse(a.created_at)) *
          (sortOrder === "oldest" ? -1 : 1),
  );
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const current = Math.min(page, pages - 1);
  const tab =
    mode === "investigations"
      ? "automation"
      : mode === "exceptions"
        ? "exceptions"
        : mode === "corrections"
          ? "corrections"
          : mode === "preflight"
            ? "preflight"
            : "overview";
  return (
    <div className="page-stack">
      <PageHeader
        title={
          view === "assigned"
            ? "Assigned to you"
            : view === "corrections"
              ? "Awaiting customer corrections"
              : view === "ready"
                ? "Ready for approval"
                : titles[mode]
        }
        description={
          view === "assigned"
            ? "Cases owned by your signed-in staff identity."
            : view === "corrections"
              ? "Customer action is required before these cases can proceed."
              : view === "ready"
                ? "Current, checked cases without open findings or risk holds. Review the evidence before deciding."
                : descriptions[mode]
        }
      ></PageHeader>
      {mode === "operations" ? (
        <SectionNav
          label="Operations views"
          items={[
            { to: "/operations", label: "Handed-over packages" },
            { to: "/audit", label: "Handover history" },
          ]}
        />
      ) : mode === "exceptions" ? (
        <SectionNav
          label="Exception views"
          items={[
            { to: "/exceptions", label: "All open issues" },
            { to: "/corrections", label: "Correction tasks" },
            { to: "/investigations", label: "AI investigations" },
          ]}
        />
      ) : (
        <SectionNav label="Submission views" items={submissionNav} />
      )}
      <Panel>
        <div className="table-toolbar">
          <label className="search-field">
            <span className="sr-only">Search submissions</span>
            <input
              type="search"
              placeholder="Search customer or reference"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setPage(0);
              }}
            />
          </label>
          <select
            aria-label="Filter by status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">All statuses</option>
            {[
              "DRAFT",
              "PENDING_VALIDATION",
              "CORRECTION_REQUIRED",
              "RESOLUTION_MANAGEMENT",
              "ON_HOLD",
              "OPERATIONS",
            ].map((value) => (
              <option key={value} value={value}>
                {value === "OPERATIONS" ? "With Operations" : label(value)}
              </option>
            ))}
          </select>
          <select
            aria-label="Filter by source"
            value={source}
            onChange={(event) => {
              setSource(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">All sources</option>
            <option value="CUSTOMER_PORTAL">Customer Portal</option>
            <option value="RM">RM upload</option>
          </select>
          <select
            aria-label="Filter by risk"
            value={riskLevel}
            onChange={(event) => {
              setRiskLevel(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">All risk levels</option>
            {["LOW", "MEDIUM", "HIGH"].map((level) => (
              <option key={level}>{level}</option>
            ))}
          </select>
          <select
            aria-label="Filter by validation"
            value={validationOutcome}
            onChange={(event) => {
              setValidationOutcome(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">All validation states</option>
            <option value="current">Current revision checked</option>
            <option value="stale">Needs validation</option>
          </select>
          <select
            aria-label="Filter by request type"
            value={requestType}
            onChange={(event) => {
              setRequestType(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">All request types</option>
            {[...new Set((result.data || []).map((item) => item.category))]
              .sort()
              .map((value) => (
                <option key={value} value={value}>
                  {label(value)}
                </option>
              ))}
          </select>
          <select
            aria-label="Sort submissions"
            value={sortOrder}
            onChange={(event) => {
              setSortOrder(event.target.value);
              setPage(0);
            }}
          >
            <option value="risk">Risk priority</option>
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
            <option value="customer">Customer name</option>
          </select>
          <span className="result-count">
            {result.error
              ? "Unavailable"
              : result.loading
                ? "Loading..."
                : `${items.length} ${items.length === 1 ? "submission" : "submissions"}`}
          </span>
        </div>
        {result.error ? (
          <ErrorBox
            message={result.error}
            retry={() => void result.refresh()}
          />
        ) : result.loading ? (
          <Loading />
        ) : (
          <SubmissionTable
            items={items.slice(current * pageSize, (current + 1) * pageSize)}
            tab={tab}
            filtered={
              !!search ||
              status !== "all" ||
              source !== "all" ||
              requestType !== "all"
            }
          />
        )}
        {items.length > pageSize && (
          <div className="table-pagination">
            <span>
              Page {current + 1} of {pages}
            </span>
            <div>
              <button
                className="button secondary"
                disabled={current === 0}
                onClick={() => setPage(current - 1)}
              >
                <ChevronLeft size={15} />
                Previous
              </button>
              <button
                className="button secondary"
                disabled={current >= pages - 1}
                onClick={() => setPage(current + 1)}
              >
                Next
                <ChevronRight size={15} />
              </button>
            </div>
          </div>
        )}
      </Panel>
    </div>
  );
}

export function DocumentReports() {
  const result = useResource<Metrics>(`${BASE}/analytics`);
  const m = result.data;
  const issueItems = m
    ? Object.entries(m.recurringErrors)
        .sort((a, b) => b[1] - a[1])
        .map(([name, count], index) => ({
          label: name,
          value: count,
          tone: index < 2 ? "danger" : index < 5 ? "warning" : "info",
        }))
    : [];
  const riskItems = m?.riskCounts
    ? [
        {
          label: "High risk",
          value: m.riskCounts.HIGH ?? 0,
          tone: "danger",
        },
        {
          label: "Medium risk",
          value: m.riskCounts.MEDIUM ?? 0,
          tone: "warning",
        },
        {
          label: "Low risk",
          value: m.riskCounts.LOW ?? 0,
          tone: "success",
        },
        {
          label: "Unassessed",
          value: m.riskCounts.UNASSESSED ?? 0,
          tone: "neutral",
        },
      ].filter((item) => item.value > 0)
    : [];
  return (
    <div className="page-stack">
      <PageHeader
        title="Reports &amp; analytics"
        description="Measured outcomes and exception frequency across your accessible submission history."
      />
      <SectionNav label="Reports views" items={reportNav} />
      {result.error ? (
        <ErrorBox message={result.error} retry={() => void result.refresh()} />
      ) : !m ? (
        <Loading />
      ) : (
        <>
          <MetricStrip
            items={[
              {
                label: "Automatically resolved",
                value: m.automaticallyResolved,
                note: "Verified autonomous attachments",
                tone: "success",
              },
              {
                label: "Replacement cycles",
                value: m.resubmissionCycles,
                note: "Document re-uploads recorded",
                tone: "default",
              },
              {
                label: "Average correction time",
                value:
                  m.averageCorrectionSeconds === null
                    ? "Not measured"
                    : `${Math.round(m.averageCorrectionSeconds)}s`,
                note: `${m.measuredCorrectionTasks} resolved tasks measured`,
                tone: "accent",
              },
              {
                label: "Human intervention",
                value: m.humanInterventionRequired,
                note: `${m.onHold ?? 0} currently on hold`,
                tone: m.humanInterventionRequired > 0 ? "warning" : "default",
              },
            ]}
          />
          <div className="content-columns">
            <Panel
              title="Common submission issues"
              subtitle="Frequency of policy findings across submitted packages."
            >
              <div className="panel-body">
                {issueItems.length ? (
                  <HorizontalBarChart
                    items={issueItems}
                    ariaLabel="Common submission issues distribution"
                  />
                ) : (
                  <Empty title="No exception history yet" />
                )}
                <p className="section-note">
                  Counts include resolved findings. Each requirement is counted
                  once per case.
                </p>
              </div>
            </Panel>
            <Panel
              title="Submission status &amp; risk posture"
              subtitle="Distribution of current case states and exception risk tiers."
            >
              <div className="panel-body">
                <StatusChart metrics={m} />
                {riskItems.length > 0 && (
                  <div className="report-subchart">
                    <div className="chart-header">
                      <h3>Exception risk distribution</h3>
                      <span>{m.onHold ?? 0} on hold</span>
                    </div>
                    <HorizontalBarChart
                      items={riskItems}
                      ariaLabel="Exception risk distribution"
                    />
                  </div>
                )}
              </div>
            </Panel>
          </div>
          {issueItems.length > 0 && (
            <Panel title="Prevention opportunities">
              <div className="panel-body">
                <p>
                  Consider an RM pre-submission checklist reminder for the top
                  recurring requirements above before forms are submitted.
                </p>
                <p className="muted">
                  Recommendations require human review and do not change
                  approved policies.
                </p>
              </div>
            </Panel>
          )}
          <Disclosure title="Measurement details">
            <dl className="summary-list">
              <div>
                <dt>Resolved tasks measured</dt>
                <dd>{m.measuredCorrectionTasks}</dd>
              </div>
              <div>
                <dt>Average submission to latest validation</dt>
                <dd>
                  {m.averageSubmissionToValidationSeconds === null
                    ? "Not measured"
                    : `${Math.round(m.averageSubmissionToValidationSeconds)} seconds`}
                </dd>
              </div>
            </dl>
            <p className="muted">
              Validation turnaround includes time waiting for customer
              corrections. It is not extraction latency or a performance
              benchmark.
            </p>
          </Disclosure>
        </>
      )}
    </div>
  );
}

export function DocumentAudit() {
  const result = useResource<Audit[]>(`${BASE}/audit`);
  const [query, setQuery] = useState("");
  const items = (result.data || []).filter((event) =>
    `${event.eventType} ${event.actor} ${event.exceptionId || ""} ${detailText(event.details)}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  return (
    <div className="page-stack">
      <PageHeader
        title="Audit history"
        description="Document decisions, corrections and handovers within your access."
      />
      <SectionNav label="Reports views" items={reportNav} />
      <Panel title="Recorded activity">
        <div className="table-toolbar">
          <input
            type="search"
            aria-label="Search audit history"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search activity or case reference"
          />
          <span className="result-count">
            {result.error
              ? "Unavailable"
              : result.loading
                ? "Loading..."
                : `${items.length} events`}
          </span>
        </div>
        {result.error ? (
          <ErrorBox message={result.error} />
        ) : result.loading ? (
          <Loading />
        ) : (
          <AuditList items={items} />
        )}
      </Panel>
    </div>
  );
}

export function DocumentDemo() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function download(name: string) {
    setError("");
    try {
      const blob = await privatePdf(`${BASE}/demo/samples/${name}`);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = name;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setError(message(error));
    }
  }
  return (
    <div className="page-stack">
      <PageHeader
        title="Demo environment"
        description="Follow a synthetic case from document upload to Operations."
      />
      <SectionNav
        label="Demonstration views"
        items={[
          { to: "/demo", label: "Document operations" },
          { to: "/legacy", label: "Legacy recovery", end: false },
        ]}
      />
      {error && <ErrorBox message={error} />}
      <Panel title="Start a demonstration">
        <div className="panel-body demo-intro">
          <div>
            <h3>Alex Tan / SYN-ALEX-001</h3>
            <p>
              Three forms, a missing supporting attachment and two customer
              corrections. A fresh case preserves earlier demo history.
            </p>
          </div>
          <button
            className="button primary"
            disabled={busy || !session.demoMode}
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                const data = await post<Detail>(`${BASE}/demo/restore`);
                navigate(`/submissions/${data.submission.id}`);
              } catch (error) {
                setError(message(error));
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "Preparing case..." : "Create demo case"}
            <ArrowRight size={16} />
          </button>
        </div>
        {!session.demoMode && (
          <p className="panel-note">
            Demo controls are disabled in this environment.
          </p>
        )}
      </Panel>
      <Panel title="Sample documents">
        {!session.demoMode && (
          <p className="panel-note">
            Sample downloads are unavailable because demo mode is disabled.
          </p>
        )}
        <div className="sample-files">
          {[
            [
              "customer-information.pdf",
              "Customer information",
              "Original form",
            ],
            ["account-application.pdf", "Account application", "Original form"],
            [
              "tax-residency.pdf",
              "Tax declaration",
              "Original form with issues",
            ],
            [
              "tax-residency-corrected.pdf",
              "Corrected tax declaration",
              "Use for the replacement step",
            ],
          ].map(([name, title, note]) => (
            <div key={name}>
              <FileText size={20} />
              <span>
                <strong>{title}</strong>
                <small>{note}</small>
              </span>
              <button
                className="button secondary"
                disabled={!session.demoMode}
                onClick={() => void download(name)}
                aria-label={`Download ${title}`}
              >
                <Download size={15} />
                Download
              </button>
            </div>
          ))}
        </div>
      </Panel>
      <Panel title="Walkthrough">
        <ol className="walkthrough">
          <li>
            <strong>Upload the three original forms</strong>
            <p>
              Gemini reads the evidence and approved rules identify the issues.
            </p>
          </li>
          <li>
            <strong>Review the automatic resolution</strong>
            <p>
              Open Automation to check whether the supporting document was
              attached and verified. Customer corrections remain for dates and
              conflicting addresses.
            </p>
          </li>
          <li>
            <strong>Replace the tax declaration</strong>
            <p>
              Choose the tax document, select Replace PDF and upload the
              corrected declaration.
            </p>
          </li>
          <li>
            <strong>Review the handover</strong>
            <p>
              The package is checked again and sent to Operations when every
              blocking requirement passes.
            </p>
          </li>
        </ol>
      </Panel>
      <p className="page-note">
        These are fictional AFI templates, not official bank forms.{" "}
        <Link to="/legacy">Open the separate transaction recovery demo.</Link>
      </p>
    </div>
  );
}
