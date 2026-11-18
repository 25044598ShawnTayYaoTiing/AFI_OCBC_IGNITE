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
  Empty,
  ErrorBox,
  Loading,
  PageHeader,
  Panel,
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
  SubmissionTable,
} from "./DocumentShared";

export function DocumentOverview() {
  const cases = useResource<Submission[]>(`${BASE}/submissions`, 15000);
  const metrics = useResource<Metrics>(`${BASE}/analytics`, 15000);
  const attention = (cases.data || []).filter(needsAttention).length;
  return (
    <div className="page-stack">
      <PageHeader title="Overview" description="Review customer requests, see what was resolved automatically, and send verified cases to Operations.">
        <Link className="button primary" to="/exceptions">Review cases needing attention <ArrowRight size={16} /></Link>
      </PageHeader>
      {cases.error && <ErrorBox message="Could not load cases." retry={() => void cases.refresh()} />}
      {metrics.error && <ErrorBox message="Could not load case totals." retry={() => void metrics.refresh()} />}
      <MetricStrip items={[
        { label: "Require attention", value: cases.data ? attention : "—" },
        { label: "Resolved automatically", value: metrics.data?.automaticallyResolved ?? "—" },
        { label: "Sent to Operations", value: metrics.data?.successfullyRouted ?? "—" },
      ]} />
      <Panel title="Recent cases" action={<Link className="text-link" to="/submissions">View all cases <ArrowRight size={15} /></Link>}>
        {cases.loading ? <Loading /> : cases.data && <SubmissionTable items={cases.data.slice(0, 6)} />}
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
                {value === "OPERATIONS" ? "With Operations" : label(value)}
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
  return (
    <div className="page-stack">
      <PageHeader
        title="Reports & Audit"
        description="See case outcomes and review recorded activity."
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
              { label: "Document replacements", value: m.resubmissionCycles },
              {
                label: "Average correction time",
                value:
                  m.averageCorrectionSeconds === null
                    ? "Not measured"
                    : `${Math.round(m.averageCorrectionSeconds)}s`,
              },
              {
                label: "Needed staff review",
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
