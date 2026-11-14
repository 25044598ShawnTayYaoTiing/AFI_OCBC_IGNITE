import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
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
  const attention = items.filter((item) =>
    [
      "CORRECTION_REQUIRED",
      "HUMAN_REVIEW",
      "RESOLUTION_MANAGEMENT",
      "ON_HOLD",
    ].includes(item.status),
  );
  const value = (number: number | undefined) =>
    metrics.loading || metrics.error ? "--" : (number ?? "--");
  return (
    <div className="page-stack command-home">
      <section className="command-masthead">
        <div>
          <span className="eyebrow">
            Resolution Management · Service Operations
          </span>
          <h1>Every case. A clear way forward.</h1>
          <p>
            Investigate cross-source evidence, resolve exceptions, and approve
            verified Operations handovers.
          </p>
        </div>
        <Link className="command-focus" to="/exceptions">
          <span>Focus queue</span>
          <strong>
            {submissions.loading || submissions.error ? "—" : attention.length}
          </strong>
          <span>
            cases need attention
            <ArrowRight size={18} />
          </span>
        </Link>
      </section>
      <div className="risk-counts">
        {["LOW", "MEDIUM", "HIGH", "UNASSESSED"].map((level) => (
          <span key={level}>
            {level} risk{" "}
            <strong>
              {metrics.loading || metrics.error
                ? "--"
                : m?.riskCounts
                  ? (m.riskCounts[level] ?? 0)
                  : "--"}
            </strong>
          </span>
        ))}
        <span>
          On hold{" "}
          <strong>
            {metrics.loading || metrics.error ? "--" : (m?.onHold ?? "--")}
          </strong>
        </span>
      </div>
      {metrics.error && (
        <ErrorBox
          message={metrics.error}
          retry={() => void metrics.refresh()}
        />
      )}
      <MetricStrip
        items={[
          { label: "Total submissions", value: value(m?.totalSubmissions) },
          {
            label: "Pending validation",
            value: value(m ? m.statuses.PENDING_VALIDATION || 0 : undefined),
          },
          {
            label: "Need attention",
            value:
              submissions.loading || submissions.error
                ? "--"
                : attention.length,
          },
          {
            label: "Automatically resolved",
            value: value(m?.automaticallyResolved),
          },
          { label: "With Operations", value: value(m?.successfullyRouted) },
        ]}
      />
      <Panel
        title="Recent submissions"
        action={
          <Link className="text-link" to="/submissions">
            View all <ArrowRight size={14} />
          </Link>
        }
      >
        <ResourceState
          error={submissions.error}
          loading={submissions.loading}
          retry={() => void submissions.refresh()}
        >
          <SubmissionTable items={items.slice(0, 4)} />
        </ResourceState>
      </Panel>
      <div className="content-columns">
        <Panel
          title="Needs attention"
          action={
            <Link className="text-link" to="/exceptions">
              Open queue
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
                {attention.slice(0, 4).map((item) => (
                  <Link
                    key={item.id}
                    className="record-row"
                    to={`/submissions/${item.id}?tab=exceptions`}
                  >
                    <div>
                      <strong>{item.customer_name}</strong>
                      <small>{item.customer_reference}</small>
                    </div>
                    <Badge value={item.status} />
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
        <Panel title="Automation summary">
          <div className="panel-body">
            <dl className="summary-list">
              <div>
                <dt>Exceptions resolved automatically</dt>
                <dd>{value(m?.automaticallyResolved)}</dd>
              </div>
              <div>
                <dt>Human intervention required</dt>
                <dd>{value(m?.humanInterventionRequired)}</dd>
              </div>
              <div>
                <dt>Blocked or failed attempts</dt>
                <dd>{value(m?.failedOrBlockedAutomation)}</dd>
              </div>
            </dl>
            {!metrics.error && m && m.totalSubmissions > 0 && (
              <StatusChart metrics={m} />
            )}
            <Link className="text-link" to="/reports">
              View reports <ArrowRight size={14} />
            </Link>
          </div>
        </Panel>
      </div>
      <Panel
        title="Recent activity"
        action={
          <Link className="text-link" to="/audit">
            Audit history
          </Link>
        }
      >
        {activity.error ? (
          <ErrorBox message={activity.error} />
        ) : activity.loading ? (
          <Loading />
        ) : (
          <AuditList items={(activity.data || []).slice(0, 4)} compact />
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
    submissions: "Submissions",
    investigations: "Investigations",
    exceptions: "Resolution Management",
    corrections: "Corrections",
    operations: "Operations",
    preflight: "Preflight",
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
            : "review";
  return (
    <div className="page-stack">
      <PageHeader
        title={titles[mode]}
        description={descriptions[mode]}
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
  return (
    <div className="page-stack">
      <PageHeader
        title="Reports"
        description="Measured outcomes from your accessible submission history."
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
              },
              { label: "Replacement cycles", value: m.resubmissionCycles },
              {
                label: "Average correction time",
                value:
                  m.averageCorrectionSeconds === null
                    ? "Not measured"
                    : `${Math.round(m.averageCorrectionSeconds)}s`,
              },
              {
                label: "Human intervention",
                value: m.humanInterventionRequired,
              },
            ]}
          />
          <div className="content-columns">
            <Panel title="Common submission issues">
              <div className="panel-body">
                {Object.entries(m.recurringErrors).length ? (
                  Object.entries(m.recurringErrors)
                    .sort((a, b) => b[1] - a[1])
                    .map(([name, count]) => (
                      <div className="report-issue" key={name}>
                        <span>{name}</span>
                        <strong>{count}</strong>
                      </div>
                    ))
                ) : (
                  <Empty title="No exception history yet" />
                )}
                <p className="section-note">
                  Counts include resolved findings. Each requirement is counted
                  once per case.
                </p>
              </div>
            </Panel>
            <Panel title="Submission status">
              <div className="panel-body">
                <StatusChart metrics={m} />
              </div>
            </Panel>
          </div>
          {Object.keys(m.recurringErrors).length > 0 && (
            <Panel title="Prevention opportunities">
              <div className="panel-body">
                <p>
                  Consider an RM reminder for frequently missed requirements
                  before forms are submitted.
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
            <p>
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
