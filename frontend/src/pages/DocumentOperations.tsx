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
} from "../components";
import { useAuth } from "../auth";
import { readUiPreferences } from "../preferences";
import { Disclosure, MetricStrip, SectionNav } from "../ui";
import { nextCaseAction } from "./case-facts";
import {
  AuditList,
  BASE,
  message,
  reportNav,
  StatusChart,
  SubmissionTable,
} from "./DocumentShared";

export function DocumentOverview() {
  const cases = useResource<Submission[]>(`${BASE}/submissions`, 15000);
  const metrics = useResource<Metrics>(`${BASE}/analytics`, 15000);
  const items = cases.data || [];
  const m = metrics.data;
  const attentionList = items
    .filter(needsAttention)
    .slice()
    .sort(
      (a, b) =>
        Number(!!b.risk_hold) - Number(!!a.risk_hold) ||
        (b.risk_score ?? -1) - (a.risk_score ?? -1),
    );
  const attention = attentionList.length;
  const readyCount = items.filter(
    (item) =>
      ["RESOLUTION_MANAGEMENT", "READY"].includes(item.status) &&
      item.validated_revision === item.revision &&
      !item.open_exceptions &&
      !item.risk_hold,
  ).length;
  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="Resolution Management · Service Operations"
        title="Overview"
        description="Review customer requests, see what was resolved automatically, and send verified cases to Operations."
      >
        <Link className="button secondary" to="/submissions?view=ready">
          Ready for approval ({readyCount})
        </Link>
        <Link className="button primary" to="/exceptions">
          Review cases needing attention ({cases.data ? attention : "—"}){" "}
          <ArrowRight size={16} />
        </Link>
      </PageHeader>
      {cases.error && (
        <ErrorBox
          message="Could not load cases."
          retry={() => void cases.refresh()}
        />
      )}
      {metrics.error && (
        <ErrorBox
          message="Could not load case totals."
          retry={() => void metrics.refresh()}
        />
      )}
      <MetricStrip
        items={[
          {
            label: "Require attention",
            value: cases.data ? attention : "—",
            note: `${readyCount} ready to approve · ${m?.onHold ?? 0} on hold`,
            tone: attention > 0 ? "warning" : "default",
          },
          {
            label: "Resolved automatically",
            value: m?.automaticallyResolved ?? "—",
            note: "Verified repository attachments",
            tone: "success",
          },
          {
            label: "Completed",
            value: m?.successfullyRouted ?? "—",
            note: "Approved & handed over",
            tone: "accent",
          },
          {
            label: "Total submissions",
            value: m?.totalSubmissions ?? (cases.data ? items.length : "—"),
            note: `${m?.humanInterventionRequired ?? 0} needing human action`,
            tone: "default",
          },
        ]}
      />
      {m && (
        <div className="risk-counts" aria-label="Case risk distribution">
          <span className="risk-counts-title">Risk &amp; hold posture</span>
          {(["HIGH", "MEDIUM", "LOW"] as const).map((level) => (
            <Link
              key={level}
              to="/submissions"
              className={`risk-pill risk-pill-${level.toLowerCase()}`}
            >
              <span>{label(level)} risk</span>
              <strong>{m.riskCounts?.[level] ?? 0}</strong>
            </Link>
          ))}
          <Link to="/exceptions" className="risk-pill risk-pill-hold">
            <span>On hold</span>
            <strong>{m.onHold ?? 0}</strong>
          </Link>
        </div>
      )}
      <div className="content-columns command-primary-grid">
        <Panel
          title="Priority cases needing attention"
          subtitle="Ordered by hold status and exception risk score."
          action={
            <Link className="text-link" to="/exceptions">
              View queue ({attention}) <ArrowRight size={14} />
            </Link>
          }
        >
          {cases.loading ? (
            <Loading />
          ) : attentionList.length ? (
            <div className="record-list">
              {attentionList.slice(0, 5).map((item) => (
                <Link
                  key={item.id}
                  className="record-row priority-case-row"
                  to={`/submissions/${item.id}`}
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
                  </div>
                  <div className="priority-case-badges">
                    {item.risk_hold && <Badge value="ON_HOLD" text="On hold" />}
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
              title="All caught up"
              description="No cases currently require correction or reviewer action."
            />
          )}
        </Panel>
        <Panel
          title="Pipeline &amp; status breakdown"
          subtitle="Current distribution of submissions and automation outcomes."
          action={
            <Link className="text-link" to="/reports">
              Reports &amp; audit <ArrowRight size={14} />
            </Link>
          }
        >
          <div className="panel-body">
            <div className="automation-kpi-grid">
              <div className="automation-kpi-card tone-success">
                <span>Auto-resolved</span>
                <strong>{m?.automaticallyResolved ?? "—"}</strong>
                <small>Verified attachments</small>
              </div>
              <div className="automation-kpi-card tone-warning">
                <span>Staff / customer</span>
                <strong>{m?.humanInterventionRequired ?? "—"}</strong>
                <small>Action required</small>
              </div>
              <div className="automation-kpi-card tone-danger">
                <span>Blocked</span>
                <strong>{m?.failedOrBlockedAutomation ?? "—"}</strong>
                <small>Safety check blocked</small>
              </div>
            </div>
            {m && m.totalSubmissions > 0 && <StatusChart metrics={m} />}
          </div>
        </Panel>
      </div>
      <Panel
        title="Recent cases"
        action={
          <Link className="text-link" to="/submissions">
            View all cases <ArrowRight size={15} />
          </Link>
        }
      >
        {cases.loading ? (
          <Loading />
        ) : (
          cases.data && <SubmissionTable items={cases.data.slice(0, 6)} />
        )}
      </Panel>
    </div>
  );
}

const needsAttention = (item: Submission) =>
  !!item.risk_hold || ["CORRECTION_REQUIRED", "HUMAN_REVIEW", "RESOLUTION_MANAGEMENT", "ON_HOLD", "READY"].includes(item.status);

export function DocumentQueue({ mode = "submissions" }: { mode?: string }) {
  const [viewParams] = useSearchParams();
  const view = mode === "submissions" ? viewParams.get("view") : null;
  const { session } = useAuth();
  const result = useResource<Submission[]>(`${BASE}/submissions`, 15000);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [riskLevel, setRiskLevel] = useState("all");
  const [page, setPage] = useState(0);
  const pageSize = readUiPreferences().pageSize;
  const titles: Record<string, string> = {
    submissions: "Cases",
    investigations: "Investigations",
    exceptions: "Requires Attention",
    corrections: "Corrections",
    operations: "Operations",
    preflight: "Handover readiness",
  };
  const descriptions: Record<string, string> = {
    submissions: "Find and review customer service requests.",
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
        ? needsAttention(item)
        : mode === "operations"
          ? item.status === "OPERATIONS"
          : true,
    )
    .filter(
      (item) =>
        (riskLevel === "all" || item.risk_level === riskLevel) &&
        (status === "all" || item.status === status) &&
        `${item.customer_name} ${item.customer_reference} ${item.id}`
          .toLowerCase()
          .includes(search.toLowerCase()),
    );
  items.sort((a, b) => mode === "exceptions"
    ? Number(!!b.risk_hold) - Number(!!a.risk_hold) || (b.risk_score ?? -1) - (a.risk_score ?? -1)
    : Date.parse(b.created_at) - Date.parse(a.created_at));
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
      <Panel>
        <div className="table-toolbar">
          <label className="search-field">
            <span className="sr-only">Search cases</span>
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
                {value === "OPERATIONS" ? "Completed" : label(value)}
              </option>
            ))}
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
            filtered={!!search || status !== "all" || riskLevel !== "all"}
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
        title="Reports & Audit"
        description="See case outcomes, recurring exception patterns and recorded activity."
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
                note: "Verified repository attachments",
                tone: "success",
              },
              {
                label: "Document replacements",
                value: m.resubmissionCycles,
                note: "Customer resubmission cycles",
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
                label: "Needed staff review",
                value: m.humanInterventionRequired,
                note: `${m.onHold ?? 0} currently on hold`,
                tone: m.humanInterventionRequired > 0 ? "warning" : "default",
              },
            ]}
          />
          <div className="content-columns reports-analytics-grid">
            <Panel
              title="Common submission issues"
              subtitle="Frequency of findings across submitted packages."
            >
              <div className="panel-body">
                {issueItems.length ? (
                  <div
                    className="report-issue-grid"
                    aria-label="Common submission issues distribution"
                    role="list"
                  >
                    {issueItems.map((item) => (
                      <div
                        className="report-issue-item"
                        key={item.label}
                        role="listitem"
                      >
                        <span>{item.label}</span>
                        <strong>{item.value}</strong>
                      </div>
                    ))}
                  </div>
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
              title="Submission status &amp; risk"
              subtitle="Current case states and exception risk tiers."
            >
              <div className="panel-body">
                <StatusChart metrics={m} />
                {riskItems.length > 0 && (
                  <div className="report-subchart">
                    <div className="chart-header">
                      <h3>Exception risk tiers</h3>
                      <span>{m.onHold ?? 0} on hold</span>
                    </div>
                    <div
                      className="report-risk-list"
                      aria-label="Exception risk distribution"
                      role="list"
                    >
                      {riskItems.map((item) => (
                        <div
                          className="report-issue-item"
                          key={item.label}
                          role="listitem"
                        >
                          <span>{item.label}</span>
                          <strong>{item.value}</strong>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </Panel>
          </div>
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
        description="Follow a demo case from document upload to Operations."
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
