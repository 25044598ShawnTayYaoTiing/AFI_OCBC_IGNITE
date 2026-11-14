import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  AlertTriangle,
  Boxes,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  Clock3,
  FileSearch,
  Layers3,
  ListFilter,
  LoaderCircle,
  RefreshCw,
  ScanLine,
  SearchCheck,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  Users,
  Zap,
} from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  dateKey,
  dateTime,
  formatNumber,
  label,
  post,
  useResource,
} from "../api";
import type { Audit, Case, Investigation, Page, Summary } from "../api";
import { Disclosure, MetricStrip, Tabs, TabPanel } from "../ui";
import { readUiPreferences } from "../preferences";
import {
  Badge,
  CaseTable,
  Empty,
  ErrorBox,
  Loading,
  Notice,
  OperationStatus,
  PageHeader,
  Panel,
  SearchBox,
  Timeline,
  Workflow,
  useOperation,
} from "../components";

const chartColors = ["#4e75dc", "#47bca6", "#e4ae50", "#b18bd4", "#e77d7d"];
export function Overview() {
  const summary = useResource<Summary>("/api/dashboard/summary", 15000);
  const activity = useResource<Audit[]>("/api/dashboard/activity", 15000);
  const cases = useResource<Page<Case>>(
    "/api/exceptions?page=0&size=100&sort=detectedAt,desc",
    15000,
  );
  const criticalCases = useResource<Page<Case>>(
    "/api/exceptions?severity=CRITICAL&size=1",
  );
  const operation = useOperation();
  const [range, setRange] = useState(readUiPreferences().defaultRange);
  const rangeStart =
    range === "all" ? 0 : Date.now() - Number(range) * 24 * 60 * 60 * 1000;
  const rangeCases = (cases.data?.items || []).filter(
    (item) => new Date(item.detectedAt).getTime() >= rangeStart,
  );
  const rangeActivity = (activity.data || []).filter(
    (event) => new Date(event.timestamp).getTime() >= rangeStart,
  );
  const rangeCaseIds = new Set(rangeCases.map((item) => item.id));
  const openCount =
    range === "all"
      ? summary.data?.activeExceptions || 0
      : rangeCases.filter((item) => item.status !== "RESOLVED").length;
  const pendingCount =
    range === "all"
      ? summary.data?.awaitingApproval || 0
      : rangeCases.filter((item) => item.status === "AWAITING_APPROVAL").length;
  const autoResolvedCount =
    range === "all"
      ? summary.data?.automatedAttempts.successful || 0
      : new Set(
          rangeActivity
            .filter(
              (event) =>
                /VERIFICATION_COMPLETED|VERIFICATION_PASSED/.test(
                  event.eventType,
                ) &&
                event.exceptionId &&
                rangeCaseIds.has(event.exceptionId),
            )
            .map((event) => event.exceptionId)
            .filter(Boolean),
        ).size;
  const resolvedInRange = rangeCases.filter(
    (item) => item.status === "RESOLVED" && item.resolvedAt,
  );
  const averageSeconds =
    range === "all"
      ? summary.data?.averageResolutionSeconds || 0
      : resolvedInRange.length
        ? resolvedInRange.reduce(
            (sum, item) =>
              sum +
              (new Date(item.resolvedAt!).getTime() -
                new Date(item.detectedAt).getTime()) /
                1000,
            0,
          ) / resolvedInRange.length
        : 0;
  const averageLabel =
    averageSeconds >= 3600
      ? `${formatNumber(averageSeconds / 3600)}h`
      : averageSeconds >= 60
        ? `${formatNumber(averageSeconds / 60)}m`
        : `${formatNumber(averageSeconds)}s`;
  const daily = new Map<
    string,
    { date: string; detected: number; resolved: number }
  >();
  for (const item of rangeCases) {
    const detectedDate = dateKey(item.detectedAt);
    const detectedBucket = daily.get(detectedDate) || {
      date: detectedDate,
      detected: 0,
      resolved: 0,
    };
    detectedBucket.detected += 1;
    daily.set(detectedDate, detectedBucket);
    if (item.resolvedAt) {
      const resolvedDate = dateKey(item.resolvedAt);
      const resolvedBucket = daily.get(resolvedDate) || {
        date: resolvedDate,
        detected: 0,
        resolved: 0,
      };
      resolvedBucket.resolved += 1;
      daily.set(resolvedDate, resolvedBucket);
    }
  }
  const shownDays = range === "all" ? 30 : Number(range);
  for (let offset = shownDays - 1; offset >= 0; offset--) {
    const date = dateKey(
      new Date(Date.now() - offset * 86400000).toISOString(),
    );
    if (!daily.has(date)) daily.set(date, { date, detected: 0, resolved: 0 });
  }
  const trend = [...daily.values()].sort((a, b) =>
    a.date.localeCompare(b.date),
  );
  const categories =
    range === "all"
      ? summary.data?.categories || []
      : [
          ...rangeCases.reduce(
            (map, item) => map.set(item.type, (map.get(item.type) || 0) + 1),
            new Map<string, number>(),
          ),
        ].map(([name, value]) => ({ name, value }));
  const priorityCases = rangeCases
    .filter(
      (item) =>
        item.status !== "RESOLVED" &&
        (item.severity === "CRITICAL" ||
          item.severity === "HIGH" ||
          [
            "AWAITING_APPROVAL",
            "MANUAL_REVIEW",
            "VERIFICATION_FAILED",
          ].includes(item.status)),
    )
    .slice(0, 5);
  const featured =
    rangeCases.find((item) =>
      [
        "INVESTIGATED",
        "SIMULATED",
        "AWAITING_APPROVAL",
        "READY_TO_RESOLVE",
        "RESOLVED",
      ].includes(item.status),
    ) ||
    rangeCases.find((item) => item.status !== "RESOLVED") ||
    rangeCases[0];
  const featuredInvestigation = useResource<Investigation>(
    featured ? `/api/exceptions/${featured.id}/investigation` : null,
  );
  const refresh = async () => {
    await Promise.all([
      summary.refresh(),
      activity.refresh(),
      cases.refresh(),
      criticalCases.refresh(),
    ]);
  };
  const metrics = summary.data
    ? [
        {
          label: "Open exceptions",
          value: formatNumber(openCount),
          hint:
            range === "all"
              ? "Requiring operational attention"
              : `Detected in the last ${range} days`,
          icon: Activity,
          tone: "amber",
        },
        {
          label: "Critical cases",
          value: formatNumber(
            range === "all"
              ? criticalCases.data?.total
              : rangeCases.filter((item) => item.severity === "CRITICAL")
                  .length,
          ),
          hint:
            range === "all"
              ? "Across the current case register"
              : "Within the selected period",
          icon: AlertTriangle,
          tone: "red",
        },
        {
          label: "Pending approvals",
          value: formatNumber(pendingCount),
          hint: "Awaiting a Resolution Management decision",
          icon: Users,
          tone: "purple",
        },
        {
          label: "Auto-resolved cases",
          value: formatNumber(autoResolvedCount),
          hint: "Verified resolution actions",
          icon: Zap,
          tone: "blue",
        },
        {
          label: "SLA breaches",
          value: "Not tracked",
          hint: "No SLA policy is configured",
          icon: Clock3,
          tone: "neutral",
        },
        {
          label: "Avg. resolution time",
          value: averageLabel,
          hint: "Across verified resolutions",
          icon: Clock3,
          tone: "green",
        },
      ]
    : [];
  const overviewError =
    summary.error ||
    cases.error ||
    activity.error ||
    (range === "all" ? criticalCases.error : "");
  const overviewLoading =
    summary.loading ||
    cases.loading ||
    activity.loading ||
    (range === "all" && criticalCases.loading);
  const statuses =
    range === "all"
      ? summary.data?.statuses || []
      : [
          ...rangeCases.reduce(
            (map, item) =>
              map.set(item.status, (map.get(item.status) || 0) + 1),
            new Map<string, number>(),
          ),
        ].map(([name, value]) => ({ name, value }));
  return (
    <div className="page-stack legacy-overview">
      <PageHeader
        title="Recovery overview"
        description="Track transaction exceptions and verified recovery actions."
      >
        <label className="range-select">
          Time range
          <select
            value={range}
            onChange={(event) => setRange(event.target.value as typeof range)}
            aria-label="Dashboard time range"
          >
            <option value="7">Last 7 days</option>
            <option value="30">Last 30 days</option>
            <option value="90">Last 90 days</option>
            <option value="all">All available records</option>
          </select>
        </label>
        <button className="button secondary" onClick={() => void refresh()}>
          <RefreshCw size={15} />
          Refresh
        </button>
        <button
          className="button primary"
          disabled={!!operation.busy}
          onClick={() =>
            void operation.run("detect", async () => {
              const result = await post<{ detected: number }>(
                "/api/exceptions/detect",
              );
              operation.setMessage(
                `Detection complete. ${result.detected} new exception${result.detected === 1 ? "" : "s"} found.`,
              );
              await refresh();
            })
          }
        >
          {operation.busy ? (
            <LoaderCircle size={16} className="spin" />
          ) : (
            <ScanLine size={16} />
          )}
          Run detection
        </button>
      </PageHeader>
      <OperationStatus
        error={operation.error}
        message={overviewError ? "" : operation.message}
      />
      {overviewError ? (
        <ErrorBox message={overviewError} retry={() => void refresh()} />
      ) : overviewLoading ? (
        <Loading text="Loading recovery overview..." />
      ) : (
        <>
          <MetricStrip
            items={metrics
              .filter((metric) => metric.label !== "SLA breaches")
              .map((metric) => ({
                label: metric.label,
                value: metric.value,
                note: metric.hint,
              }))}
          />
          {cases.data && cases.data.total > cases.data.items.length && (
            <p className="page-note">
              Lists and the activity chart use the latest{" "}
              {cases.data.items.length} of {cases.data.total} cases. All-history
              summary indicators use the complete register.
            </p>
          )}
          <div className="content-columns">
            <Panel
              title="Needs attention"
              action={
                <Link className="text-link" to="/legacy/exceptions">
                  Open queue
                </Link>
              }
            >
              {priorityCases.length ? (
                <div className="record-list">
                  {priorityCases.slice(0, 4).map((item) => (
                    <Link
                      key={item.id}
                      to={`/legacy/exceptions/${item.id}`}
                      className="record-row"
                    >
                      <div>
                        <strong>{label(item.type)}</strong>
                        <small>{item.id}</small>
                      </div>
                      <Badge value={item.status} />
                      <ArrowRight size={16} />
                    </Link>
                  ))}
                </div>
              ) : (
                <Empty
                  title="No priority cases"
                  description="No critical, high-severity or approval cases are in this view."
                />
              )}
            </Panel>
            <Panel
              title="Latest exceptions"
              action={
                <Link className="text-link" to="/legacy/exceptions">
                  View all
                </Link>
              }
            >
              <CaseTable items={rangeCases.slice(0, 4)} compact />
            </Panel>
          </div>
          <Panel
            title="Exception activity"
            subtitle="Daily detection and verified resolution within the selected records."
            action={
              <div className="chart-legend">
                <span>Detected</span>
                <span>Resolved</span>
              </div>
            }
          >
            {rangeCases.length ? (
              <div className="legacy-trend">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart
                    data={trend}
                    margin={{ top: 15, right: 24, left: 0, bottom: 0 }}
                  >
                    <CartesianGrid
                      strokeDasharray="3 4"
                      vertical={false}
                      stroke="var(--border)"
                    />
                    <XAxis
                      dataKey="date"
                      tickFormatter={(value) => String(value).slice(5)}
                      tickLine={false}
                      axisLine={false}
                      fontSize={12}
                      tick={{ fill: "var(--muted)" }}
                    />
                    <YAxis
                      allowDecimals={false}
                      tickLine={false}
                      axisLine={false}
                      fontSize={12}
                      tick={{ fill: "var(--muted)" }}
                    />
                    <Tooltip
                      contentStyle={{
                        background: "var(--surface)",
                        color: "var(--text)",
                        borderColor: "var(--border)",
                        fontSize: 13,
                      }}
                    />
                    <Area
                      type="monotone"
                      dataKey="detected"
                      name="Detected"
                      stroke="var(--blue)"
                      fill="#3155bd15"
                    />
                    <Area
                      type="monotone"
                      dataKey="resolved"
                      name="Resolved"
                      stroke="var(--success-text)"
                      fill="transparent"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <Empty
                title="No exceptions in this period"
                description="Choose a wider time range to review older activity."
              />
            )}
          </Panel>
          <Panel
            title="Recent activity"
            action={
              <Link className="text-link" to="/legacy/audit">
                Audit history
              </Link>
            }
          >
            <Timeline events={rangeActivity.slice(0, 4)} compact />
          </Panel>
          <Disclosure title="More operational detail">
            <div className="operational-details">
              <section>
                <h3>Categories</h3>
                <dl className="summary-list">
                  {categories.map((category) => (
                    <div key={category.name}>
                      <dt>{label(category.name)}</dt>
                      <dd>{category.value}</dd>
                    </div>
                  ))}
                </dl>
                {!categories.length && (
                  <p className="muted">No categories in these records.</p>
                )}
              </section>
              <section>
                <h3>Resolution status</h3>
                <dl className="summary-list">
                  {statuses.map((status) => (
                    <div key={status.name}>
                      <dt>{label(status.name)}</dt>
                      <dd>{status.value}</dd>
                    </div>
                  ))}
                </dl>
                {!statuses.length && (
                  <p className="muted">No cases in these records.</p>
                )}
              </section>
              <section>
                <h3>Automation and controls</h3>
                <dl className="summary-list">
                  <div>
                    <dt>Verified attempts</dt>
                    <dd>
                      {formatNumber(summary.data?.automatedAttempts.successful)}
                    </dd>
                  </div>
                  <div>
                    <dt>Failed attempts</dt>
                    <dd>
                      {formatNumber(summary.data?.automatedAttempts.failed)}
                    </dd>
                  </div>
                  <div>
                    <dt>SLA breaches</dt>
                    <dd>Not tracked</dd>
                  </div>
                </dl>
                <p className="section-note">
                  Attempt counts cover the current dataset. No SLA policy is
                  configured.
                </p>
              </section>
            </div>
          </Disclosure>
          {featured && (
            <Disclosure title="Featured case investigation">
              <div className="decision-support">
                <div className="decision-case">
                  <span>{featured.id}</span>
                  <Badge value={featured.status} />
                </div>
                <h3>{label(featured.type)}</h3>
                <p>{featured.description}</p>
                {featuredInvestigation.error ? (
                  <ErrorBox
                    message={featuredInvestigation.error}
                    retry={() => void featuredInvestigation.refresh()}
                  />
                ) : featuredInvestigation.loading ? (
                  <Loading text="Loading investigation findings..." />
                ) : featuredInvestigation.data ? (
                  <>
                    <div className="decision-evidence">
                      <strong>Probable root cause</strong>
                      <p>{featuredInvestigation.data.probableRootCause}</p>
                      <small>
                        {featuredInvestigation.data.evidence[0] ||
                          featuredInvestigation.data.explanation}
                      </small>
                    </div>
                    <p className="section-note">
                      {featuredInvestigation.data.method === "GEMINI"
                        ? "Live Gemini"
                        : "Rule-based fallback"}{" "}
                      / proposed:{" "}
                      {featuredInvestigation.data.candidateActions
                        .map(label)
                        .join(", ") || "Manual review"}
                    </p>
                  </>
                ) : (
                  <Notice>
                    Investigation findings are not available yet. Start an
                    investigation from the case workspace.
                  </Notice>
                )}
                <Link
                  to={`/legacy/exceptions/${featured.id}`}
                  className="button secondary"
                >
                  Review case <ArrowRight size={15} />
                </Link>
              </div>
            </Disclosure>
          )}
        </>
      )}
    </div>
  );
}

export function Exceptions() {
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [status, setStatus] = useState("");
  const [severity, setSeverity] = useState("");
  const [page, setPage] = useState(0);
  const [pageSize] = useState(readUiPreferences().pageSize);
  const [sort, setSort] = useState("detectedAt,desc");
  const op = useOperation();
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 250);
    return () => clearTimeout(timer);
  }, [search]);
  const params = new URLSearchParams({
    search: debounced,
    status,
    severity,
    page: String(page),
    size: String(pageSize),
    sort,
  });
  const result = useResource<Page<Case>>(`/api/exceptions?${params}`, 10000);
  return (
    <>
      <PageHeader
        eyebrow="CASE WORKSPACE"
        title="Exception Queue"
        description="Find the signal. Investigate the cause. Move each case forward."
      >
        <button
          className="button primary"
          disabled={!!op.busy}
          onClick={() =>
            void op.run("detect", async () => {
              const r = await post<{ detected: number }>(
                "/api/exceptions/detect",
              );
              op.setMessage(`Detection completed · ${r.detected} new cases.`);
              await result.refresh();
            })
          }
        >
          <ScanLine size={16} className={op.busy ? "spin" : ""} />
          Run detection
        </button>
      </PageHeader>
      <OperationStatus {...op} />
      <Panel>
        <div className="table-toolbar">
          <SearchBox
            value={search}
            onChange={setSearch}
            placeholder="Search by ID, batch, or description…"
          />
          <div className="table-filters">
            <ListFilter size={17} className="muted" />
            <select
              aria-label="Filter by status"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All statuses</option>
              {[
                "DETECTED",
                "INVESTIGATING",
                "INVESTIGATED",
                "SIMULATED",
                "AWAITING_APPROVAL",
                "MANUAL_REVIEW",
                "RESOLVED",
                "VERIFICATION_FAILED",
              ].map((v) => (
                <option value={v} key={v}>
                  {label(v)}
                </option>
              ))}
            </select>
            <select
              aria-label="Filter by severity"
              value={severity}
              onChange={(e) => {
                setSeverity(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All severities</option>
              {["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
            <select
              aria-label="Sort exceptions"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(0);
              }}
            >
              <option value="detectedAt,desc">Newest first</option>
              <option value="detectedAt,asc">Oldest first</option>
              <option value="severity,desc">Severity ↓</option>
              <option value="status,asc">Status A–Z</option>
            </select>
          </div>
        </div>
        {result.error ? (
          <ErrorBox
            message={result.error}
            retry={() => void result.refresh()}
          />
        ) : result.loading ? (
          <Loading />
        ) : (
          <CaseTable items={result.data?.items || []} />
        )}
        <div className="pagination">
          <span>
            {result.data?.total
              ? `${page * pageSize + 1}–${Math.min((page + 1) * pageSize, result.data.total)} of ${result.data.total} exceptions`
              : "0 exceptions"}
          </span>
          <div>
            <button
              className="button secondary small-button"
              disabled={page === 0 || result.loading}
              onClick={() => setPage(page - 1)}
            >
              <ChevronLeft size={15} />
              Previous
            </button>
            <span className="page-number">{page + 1}</span>
            <button
              className="button secondary small-button"
              disabled={
                result.loading ||
                (page + 1) * pageSize >= (result.data?.total || 0)
              }
              onClick={() => setPage(page + 1)}
            >
              Next
              <ChevronRight size={15} />
            </button>
          </div>
        </div>
      </Panel>
    </>
  );
}

function InvestigationSource({ item }: { item: Case }) {
  const hasFindings = [
    "INVESTIGATED",
    "SIMULATED",
    "AWAITING_APPROVAL",
    "READY_TO_RESOLVE",
    "RESOLVED",
  ].includes(item.status);
  const investigation = useResource<Investigation>(
    hasFindings ? `/api/exceptions/${item.id}/investigation` : null,
  );
  if (item.status === "INVESTIGATING")
    return (
      <>
        <span className="table-subtext">Analysis in progress</span>
        <span className="table-subtext">
          Detected {dateTime(item.detectedAt)} SGT
        </span>
      </>
    );
  if (!hasFindings)
    return (
      <>
        <span className="table-subtext">Not started</span>
        <span className="table-subtext">
          Detected {dateTime(item.detectedAt)} SGT
        </span>
      </>
    );
  if (investigation.loading)
    return <span className="table-subtext">Retrieving source…</span>;
  if (investigation.error)
    return <span className="table-subtext">Source unavailable</span>;
  return (
    <>
      <span className="table-subtext">
        {investigation.data?.method === "GEMINI"
          ? "Gemini AI investigation"
          : investigation.data
            ? "Deterministic fallback"
            : "Findings unavailable"}
      </span>
      {investigation.data?.completedAt && (
        <span className="table-subtext">
          Updated {dateTime(investigation.data.completedAt)} SGT
        </span>
      )}
    </>
  );
}

export function Investigations() {
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [page, setPage] = useState(0);
  const pageSize = readUiPreferences().pageSize;
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const params = new URLSearchParams({
    search: debounced,
    page: String(page),
    size: String(pageSize),
    sort: "detectedAt,desc",
  });
  const result = useResource<Page<Case>>(`/api/exceptions?${params}`, 8000);
  const items = result.data?.items || [];
  return (
    <>
      <PageHeader
        eyebrow="EVIDENCE TO UNDERSTANDING"
        title="Investigations"
        description="Review case evidence and structured findings. AI supports analysis; backend policy controls every action."
      />
      <Notice>
        Investigations clearly identify their source: live Gemini or
        deterministic fallback. Recommendations cannot directly change records.
      </Notice>
      <div className="section-toolbar">
        <h2>
          Case investigations{" "}
          <span className="count-pill">{result.data?.total ?? "—"}</span>
        </h2>
        <SearchBox value={search} onChange={setSearch} />
      </div>
      {result.error ? (
        <ErrorBox message={result.error} />
      ) : result.loading ? (
        <Loading />
      ) : !items.length ? (
        <Empty
          title="No cases to investigate"
          description="Detect an operational exception to start an investigation."
        >
          <Link className="button primary" to="/legacy/exceptions">
            Open exceptions
            <ArrowRight size={16} />
          </Link>
        </Empty>
      ) : (
        <div className="investigation-results">
          <div className="table-scroll">
            <table className="investigation-table">
              <thead>
                <tr>
                  <th>Case</th>
                  <th>Category</th>
                  <th>Investigation status, source & last update</th>
                  <th>Next action</th>
                </tr>
              </thead>
              <tbody>
                {items.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <Link
                        className="case-title"
                        to={`/legacy/exceptions/${c.id}`}
                      >
                        {c.id}
                        <ArrowUpRight size={13} />
                      </Link>
                      <small>{c.description}</small>
                    </td>
                    <td>{label(c.type)}</td>
                    <td>
                      <Badge value={c.status} />
                      <InvestigationSource item={c} />
                    </td>
                    <td>
                      <Link
                        className="text-link"
                        to={`/legacy/exceptions/${c.id}`}
                      >
                        Open case <ArrowUpRight size={14} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="pagination">
            <span>
              {result.data?.total
                ? `${page * pageSize + 1}–${Math.min((page + 1) * pageSize, result.data.total)} of ${result.data.total} investigations`
                : "0 investigations"}
            </span>
            <div>
              <button
                className="button secondary small-button"
                disabled={page === 0 || result.loading}
                onClick={() => setPage(page - 1)}
              >
                <ChevronLeft size={15} />
                Previous
              </button>
              <span className="page-number">{page + 1}</span>
              <button
                className="button secondary small-button"
                disabled={
                  result.loading ||
                  (page + 1) * pageSize >= (result.data?.total || 0)
                }
                onClick={() => setPage(page + 1)}
              >
                Next
                <ChevronRight size={15} />
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export function ExceptionDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const record = useResource<Case>(`/api/exceptions/${id}`, 4000);
  const investigation = useResource<Investigation>(
    `/api/exceptions/${id}/investigation`,
    4000,
  );
  const audit = useResource<Audit[]>(`/api/exceptions/${id}/audit`, 4000);
  const op = useOperation();
  const [tab, setTab] = useState("investigation");
  const item = record.data;
  if (record.loading) return <Loading text="Collecting exception evidence…" />;
  if (record.error)
    return (
      <ErrorBox message={record.error} retry={() => void record.refresh()} />
    );
  if (!item) return <Empty title="Exception not found" />;
  const evidence = item.evidence;
  const investigating = item.status === "INVESTIGATING";
  async function investigate() {
    await op.run("investigate", async () => {
      await post(`/api/exceptions/${id}/investigate`);
      op.setMessage(
        "Investigation started. Findings will appear here when the evidence analysis completes.",
      );
      await record.refresh();
      await investigation.refresh();
    });
  }
  return (
    <>
      <Link className="back-link" to="/legacy/exceptions">
        <ChevronLeft size={15} />
        Exception management
      </Link>
      <PageHeader
        eyebrow={item.id}
        title={label(item.type)}
        description={item.description}
      >
        <Badge value={item.severity} />
        <Badge value={item.status} />
        <button
          className="button secondary"
          onClick={() => navigate(`/legacy/audit?id=${id}`)}
        >
          <FileSearch size={16} />
          Audit trail
        </button>
      </PageHeader>
      <OperationStatus {...op} />
      <Panel className="case-summary">
        <div className="case-meta">
          <div>
            <span>Associated batch</span>
            <strong className="mono">{item.batchId || "—"}</strong>
          </div>
          <div>
            <span>Detected at · SGT</span>
            <strong>{dateTime(item.detectedAt)}</strong>
          </div>
          <div>
            <span>Assigned workflow</span>
            <strong>{label(item.workflow)}</strong>
          </div>
          <div>
            <span>Environment</span>
            <strong>
              <span className="mini-dot" />
              Synthetic records
            </strong>
          </div>
        </div>
        <Workflow status={item.status} />
      </Panel>
      <div className="detail-columns">
        <div className="detail-main">
          <Tabs
            id="legacy-case"
            label="Case workspace"
            value={tab}
            onChange={setTab}
            className="section-nav"
            items={[
              { value: "investigation", label: "Investigation" },
              { value: "evidence", label: "Source evidence" },
              { value: "activity", label: "Case activity" },
            ]}
          />
          <TabPanel id="legacy-case" value={tab}>
            {tab === "investigation" && (
              <Panel
                title="Understand before you act"
                subtitle="Evidence-based investigation and recovery recommendations"
                action={<Sparkles size={20} className="blue-text" />}
              >
                {investigation.error && (
                  <ErrorBox message={investigation.error} />
                )}{" "}
                {investigating ? (
                  <div className="investigating">
                    <span className="investigating-icon">
                      <ScanLine className="pulse" size={32} />
                    </span>
                    <h3>Investigating the evidence</h3>
                    <p>
                      The investigation is running in the background. This page
                      updates automatically.
                    </p>
                    <span className="loading-inline">
                      <LoaderCircle className="spin" size={15} /> Analysing
                      records and approved procedures
                    </span>
                  </div>
                ) : investigation.data ? (
                  <div className="investigation-content">
                    <div className="investigation-source">
                      <span
                        className={`method-tag ${investigation.data.method === "GEMINI" ? "live" : ""}`}
                      >
                        <Sparkles size={13} />
                        {investigation.data.method === "GEMINI"
                          ? "Live Gemini investigation"
                          : "Deterministic fallback"}
                      </span>
                      <span>
                        {dateTime(investigation.data.completedAt)} SGT
                      </span>
                    </div>
                    <div className="root-cause">
                      <span className="eyebrow">PROBABLE ROOT CAUSE</span>
                      <h3>{investigation.data.probableRootCause}</h3>
                      <p>{investigation.data.explanation}</p>
                    </div>
                    <h3 className="section-label">Supporting findings</h3>
                    <ul className="findings">
                      {investigation.data.evidence.map((e, i) => (
                        <li key={i}>
                          <Check size={16} />
                          {e}
                        </li>
                      ))}
                    </ul>
                    <h3 className="section-label">
                      Candidate recovery actions
                    </h3>
                    <div className="candidate-actions">
                      {investigation.data.candidateActions.map((a, i) => (
                        <div key={a}>
                          <span className="candidate-number">0{i + 1}</span>
                          <span>{label(a)}</span>
                          <ArrowRight size={16} />
                        </div>
                      ))}
                    </div>
                    {investigation.data.requiresAdditionalEvidence && (
                      <Notice>
                        Additional evidence is needed. This case requires human
                        review and cannot be automatically resolved.
                      </Notice>
                    )}
                    {investigation.data.fallbackReason && (
                      <p className="small muted">
                        Fallback reason: {investigation.data.fallbackReason}
                      </p>
                    )}
                    <div className="investigation-next">
                      <span>
                        <ShieldCheck size={17} />
                        Recommendations require independent validation.
                      </span>
                      <Link
                        className="button primary"
                        to={`/legacy/twin?id=${id}`}
                      >
                        Compare in digital twin
                        <ArrowRight size={16} />
                      </Link>
                    </div>
                  </div>
                ) : (
                  <Empty
                    title="The evidence is ready. Start the investigation."
                    description="Analyse the source records to identify a probable root cause and allowlisted recovery actions."
                  >
                    <button
                      className="button primary"
                      disabled={!!op.busy}
                      onClick={() => void investigate()}
                    >
                      <Sparkles size={16} />
                      {op.busy
                        ? "Starting investigation…"
                        : "Start investigation"}
                    </button>
                  </Empty>
                )}
              </Panel>
            )}
            {tab === "evidence" && (
              <Panel
                title="Operational source evidence"
                subtitle="Current records collected from the application database"
              >
                {evidence ? (
                  <>
                    <div className="evidence-stats">
                      {[
                        {
                          title: "Total records",
                          value: evidence.totalRecords,
                        },
                        {
                          title: "Processed",
                          value: evidence.processedRecords,
                        },
                        { title: "Pending", value: evidence.pendingRecords },
                        {
                          title: "Duplicate records",
                          value: evidence.duplicateRecords,
                        },
                        {
                          title: "Reconciliation mismatches",
                          value: evidence.reconciliationMismatches,
                        },
                      ].map((v) => (
                        <div key={v.title}>
                          <span>{v.title}</span>
                          <strong>{formatNumber(v.value)}</strong>
                        </div>
                      ))}
                    </div>
                    <div className="evidence-detail">
                      <h3 className="section-label">Recorded failure</h3>
                      <p className="log-line">
                        {evidence.failureReason || "No batch failure recorded."}
                      </p>
                      <h3 className="section-label">Source-state hash</h3>
                      <code className="hash">
                        {evidence.sourceHash || "Not available"}
                      </code>
                    </div>
                    <div className="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th>Record</th>
                            <th>Status</th>
                            <th>Process count</th>
                            <th>Amount</th>
                            <th>Reconciliation</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(evidence.sampleRecords || []).map((r, i) => (
                            <tr key={String(r.id || i)}>
                              <td className="mono small">{String(r.id)}</td>
                              <td>
                                <Badge value={String(r.status)} />
                              </td>
                              <td>{String(r.processingCount ?? "—")}</td>
                              <td className="nowrap">
                                {String(r.currency || "")}{" "}
                                {String(r.amount ?? "—")}
                              </td>
                              <td>
                                {r.reconciliationMatches === true
                                  ? "Matched"
                                  : r.reconciliationMatches === false
                                    ? "Mismatch"
                                    : "—"}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <p className="table-footnote">
                      Sample records shown. All records participate in safety
                      checks and simulation.
                    </p>
                  </>
                ) : (
                  <Empty title="Evidence is not yet available" />
                )}
              </Panel>
            )}
            {tab === "activity" && (
              <Panel
                title="Case activity"
                subtitle="An accountable record of each workflow step"
              >
                {audit.loading ? (
                  <Loading />
                ) : audit.error ? (
                  <ErrorBox message={audit.error} />
                ) : (
                  <Timeline events={audit.data || []} />
                )}
              </Panel>
            )}
          </TabPanel>
        </div>
        <aside className="detail-aside">
          <Panel title="Recovery workspace">
            <div className="recovery-steps">
              <div>
                <span>
                  <SearchCheck size={19} />
                </span>
                <div>
                  <h3>01 · Investigate</h3>
                  <p>Understand the cause using source evidence.</p>
                </div>
                {investigation.data && (
                  <CircleCheck className="green-text" size={16} />
                )}
              </div>
              <div>
                <span>
                  <Boxes size={19} />
                </span>
                <div>
                  <h3>02 · Simulate</h3>
                  <p>Compare strategies in an isolated snapshot.</p>
                </div>
              </div>
              <div>
                <span>
                  <ShieldCheck size={19} />
                </span>
                <div>
                  <h3>03 · Resolve & verify</h3>
                  <p>Execute only when every safety check passes.</p>
                </div>
              </div>
            </div>
            <div className="aside-actions">
              <button
                className="button secondary wide"
                disabled={
                  !!op.busy || investigating || item.status === "RESOLVED"
                }
                onClick={() => void investigate()}
              >
                <Sparkles size={15} />
                {investigating
                  ? "Investigation running"
                  : investigation.data
                    ? "Reinvestigate"
                    : "Start investigation"}
              </button>
              <Link
                className={`button ${investigation.data ? "primary" : "secondary"} wide`}
                to={`/legacy/twin?id=${id}`}
              >
                Open digital twin
                <ArrowRight size={15} />
              </Link>
              <Link
                className="button secondary wide"
                to={`/legacy/resolutions?id=${id}`}
              >
                Resolution & review
                <ArrowUpRight size={15} />
              </Link>
            </div>
          </Panel>
          <div className="guardrail-note">
            <ShieldCheck size={22} />
            <h3>Safety is part of the workflow.</h3>
            <p>
              AI investigates. Deterministic policy authorises. Stored record
              checks verify the outcome.
            </p>
            <span>NO DIRECT AI EXECUTION</span>
          </div>
          {evidence && (
            <Panel title="Current batch state">
              <div className="batch-progress">
                <div>
                  <span>Processed</span>
                  <strong>
                    {formatNumber(evidence.processedRecords)} /{" "}
                    {formatNumber(evidence.totalRecords)}
                  </strong>
                </div>
                <div className="progress-track">
                  <span
                    style={{
                      width: `${evidence.totalRecords ? (evidence.processedRecords / evidence.totalRecords) * 100 : 0}%`,
                    }}
                  />
                </div>
                <span className="muted small">
                  {formatNumber(evidence.pendingRecords)} records pending
                </span>
              </div>
            </Panel>
          )}
        </aside>
      </div>
    </>
  );
}

export function ReportsPage() {
  const summary = useResource<Summary>("/api/dashboard/summary", 30000);
  const cases = useResource<Page<Case>>(
    "/api/exceptions?size=100&sort=detectedAt,desc",
    30000,
  );
  const audit = useResource<Audit[]>("/api/audit", 30000);
  const [range, setRange] = useState("30");
  const [category, setCategory] = useState("");
  const [severity, setSeverity] = useState("");
  const [status, setStatus] = useState("");
  const rangeStart =
    range === "all" ? 0 : Date.now() - Number(range) * 86400000;
  const filteredCases = (cases.data?.items || []).filter(
    (item) =>
      new Date(item.detectedAt).getTime() >= rangeStart &&
      (!category || item.type === category) &&
      (!severity || item.severity === severity) &&
      (!status || item.status === status),
  );
  const filteredIds = new Set(filteredCases.map((item) => item.id));
  const filteredAudit = (audit.data || []).filter(
    (event) =>
      new Date(event.timestamp).getTime() >= rangeStart &&
      (!event.exceptionId || filteredIds.has(event.exceptionId)),
  );
  const fullDataset = range === "all" && !category && !severity && !status;
  const total = fullDataset
    ? summary.data?.totalExceptions || 0
    : filteredCases.length;
  const verifiedEvents = filteredAudit.filter((event) =>
    /VERIFICATION_COMPLETED|VERIFICATION_PASSED/.test(event.eventType),
  );
  const verifiedCases = new Set(
    verifiedEvents.map((event) => event.exceptionId).filter(Boolean),
  );
  const manualCases = new Set(
    filteredAudit
      .filter((event) => /APPROVAL/.test(event.eventType))
      .map((event) => event.exceptionId)
      .filter(Boolean),
  );
  const failedEvents = filteredAudit.filter((event) =>
    /FAILED|VERIFICATION_FAILED/.test(
      `${event.eventType} ${event.result}`.toUpperCase(),
    ),
  );
  const resolutions = filteredCases.filter(
    (item) => item.status === "RESOLVED" && item.resolvedAt,
  );
  const meanSeconds = fullDataset
    ? summary.data?.averageResolutionSeconds || 0
    : resolutions.length
      ? resolutions.reduce(
          (sum, item) =>
            sum +
            (new Date(item.resolvedAt!).getTime() -
              new Date(item.detectedAt).getTime()) /
              1000,
          0,
        ) / resolutions.length
      : 0;
  const duration =
    meanSeconds <= 0
      ? "—"
      : meanSeconds >= 3600
        ? `${formatNumber(meanSeconds / 3600)} h`
        : meanSeconds >= 60
          ? `${formatNumber(meanSeconds / 60)} min`
          : `${formatNumber(meanSeconds)} sec`;
  const byDay = new Map<
    string,
    { date: string; detected: number; resolved: number }
  >();
  for (const item of filteredCases) {
    const date = dateKey(item.detectedAt);
    const bucket = byDay.get(date) || { date, detected: 0, resolved: 0 };
    bucket.detected++;
    byDay.set(date, bucket);
    if (item.resolvedAt) {
      const resolvedDate = dateKey(item.resolvedAt);
      const resolvedBucket = byDay.get(resolvedDate) || {
        date: resolvedDate,
        detected: 0,
        resolved: 0,
      };
      resolvedBucket.resolved++;
      byDay.set(resolvedDate, resolvedBucket);
    }
  }
  const trend = [...byDay.values()]
    .sort((a, b) => a.date.localeCompare(b.date))
    .slice(-30);
  const groupCount = (key: "type" | "severity" | "status") => {
    const grouped = new Map<string, number>();
    for (const item of filteredCases)
      grouped.set(item[key], (grouped.get(item[key]) || 0) + 1);
    return [...grouped.entries()].sort((a, b) => b[1] - a[1]);
  };
  const categories = groupCount("type");
  const severities = groupCount("severity");
  const statuses = groupCount("status");
  const metricItems = [
    {
      name: "Detected exceptions",
      value: formatNumber(total),
      hint: fullDataset ? "All persisted cases" : "Matching selected filters",
      icon: Layers3,
      tone: "blue",
    },
    {
      name: "Auto-resolved cases",
      value: formatNumber(
        fullDataset
          ? summary.data?.automatedAttempts.successful
          : verifiedCases.size,
      ),
      hint: "Verified recovery outcomes",
      icon: CircleCheck,
      tone: "green",
    },
    {
      name: "Manually reviewed",
      value: formatNumber(manualCases.size),
      hint: "Cases with a recorded approval event",
      icon: Users,
      tone: "purple",
    },
    {
      name: "Failed attempts",
      value: formatNumber(
        fullDataset
          ? summary.data?.automatedAttempts.failed
          : failedEvents.length,
      ),
      hint: "Recorded failed resolution actions",
      icon: TriangleAlert,
      tone: "red",
    },
    {
      name: "Average resolution",
      value: duration,
      hint: "Detection to verified resolution",
      icon: Clock3,
      tone: "amber",
    },
  ];
  const chartRange =
    range === "all" ? "All available dates" : `Last ${range} days`;
  return (
    <>
      <PageHeader
        eyebrow="OPERATIONAL INSIGHT"
        title="Reports & Analytics"
        description="Performance measures and exception patterns calculated from persisted case and audit records."
      />
      <Panel className="report-filter-panel">
        <div className="report-filters">
          <label>
            Date range
            <select
              value={range}
              onChange={(event) => setRange(event.target.value)}
            >
              <option value="7">Last 7 days</option>
              <option value="30">Last 30 days</option>
              <option value="90">Last 90 days</option>
              <option value="all">All available dates</option>
            </select>
          </label>
          <label>
            Exception category
            <select
              value={category}
              onChange={(event) => setCategory(event.target.value)}
            >
              <option value="">All categories</option>
              {[...new Set((cases.data?.items || []).map((item) => item.type))]
                .sort()
                .map((type) => (
                  <option key={type} value={type}>
                    {label(type)}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Severity
            <select
              value={severity}
              onChange={(event) => setSeverity(event.target.value)}
            >
              <option value="">All severities</option>
              {[
                ...new Set(
                  (cases.data?.items || []).map((item) => item.severity),
                ),
              ]
                .sort()
                .map((level) => (
                  <option key={level} value={level}>
                    {label(level)}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Resolution status
            <select
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <option value="">All statuses</option>
              {[
                ...new Set(
                  (cases.data?.items || []).map((item) => item.status),
                ),
              ]
                .sort()
                .map((value) => (
                  <option key={value} value={value}>
                    {label(value)}
                  </option>
                ))}
            </select>
          </label>
          <button
            className="button secondary"
            onClick={() =>
              void Promise.all([
                summary.refresh(),
                cases.refresh(),
                audit.refresh(),
              ])
            }
          >
            <RefreshCw size={15} />
            Refresh
          </button>
        </div>
        {cases.data && cases.data.total > cases.data.items.length && (
          <p className="report-coverage">
            Filters and case breakdowns use the newest {cases.data.items.length}{" "}
            of {cases.data.total} cases returned by the current API page.
            Summary totals remain complete.
          </p>
        )}
      </Panel>
      {summary.error || cases.error || audit.error ? (
        <ErrorBox
          message={summary.error || cases.error || audit.error}
          retry={() =>
            void Promise.all([
              summary.refresh(),
              cases.refresh(),
              audit.refresh(),
            ])
          }
        />
      ) : summary.loading || cases.loading || audit.loading ? (
        <Loading text="Loading operational reports…" />
      ) : (
        <>
          <div className="report-kpis">
            {metricItems.map((item) => (
              <article className="metric" key={item.name}>
                <div className="metric-top">
                  <span>{item.name}</span>
                  <span className={`metric-icon ${item.tone}`}>
                    <item.icon size={17} />
                  </span>
                </div>
                <strong>{item.value}</strong>
                <p>{item.hint}</p>
              </article>
            ))}
          </div>
          <div className="report-grid">
            <Panel title="Exception volume and trend" subtitle={chartRange}>
              {trend.length ? (
                <div className="trend-chart report-trend">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart
                      data={trend}
                      margin={{ top: 12, right: 20, left: -20, bottom: 0 }}
                    >
                      <CartesianGrid
                        strokeDasharray="3 4"
                        vertical={false}
                        stroke="#edf0f4"
                      />
                      <XAxis
                        dataKey="date"
                        tickFormatter={(value) => String(value).slice(5)}
                        tickLine={false}
                        axisLine={false}
                        fontSize={12}
                      />
                      <YAxis
                        allowDecimals={false}
                        tickLine={false}
                        axisLine={false}
                        fontSize={12}
                      />
                      <Tooltip
                        contentStyle={{
                          background: "var(--surface)",
                          color: "var(--text)",
                          borderColor: "var(--border)",
                          fontSize: 13,
                        }}
                        itemStyle={{ color: "var(--text)" }}
                      />
                      <Area
                        type="monotone"
                        dataKey="detected"
                        name="Detected"
                        stroke="#5479dd"
                        fill="#5479dd22"
                      />
                      <Area
                        type="monotone"
                        dataKey="resolved"
                        name="Resolved"
                        stroke="#36ad97"
                        fill="transparent"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <Empty
                  title="No cases in this period"
                  description="Adjust the date range or case filters to see activity."
                />
              )}
            </Panel>
            <Panel
              title="Resolution performance"
              subtitle="Based on the selected case and audit records"
            >
              <div className="performance-list">
                <div>
                  <span>Auto-resolution rate</span>
                  <strong>
                    {total
                      ? `${formatNumber((verifiedCases.size / total) * 100)}%`
                      : "—"}
                  </strong>
                </div>
                <div>
                  <span>Verification success rate</span>
                  <strong>
                    {verifiedEvents.length
                      ? `${formatNumber((verifiedEvents.filter((event) => !/FAIL/.test(event.result.toUpperCase())).length / verifiedEvents.length) * 100)}%`
                      : "—"}
                  </strong>
                </div>
                <div>
                  <span>Escalation rate</span>
                  <strong>
                    {total
                      ? `${formatNumber((manualCases.size / total) * 100)}%`
                      : "—"}
                  </strong>
                </div>
                <div>
                  <span>Resolution duration</span>
                  <strong>{duration}</strong>
                </div>
              </div>
              <p className="report-note">
                Percentages are calculated from available selected records. No
                historical comparison is shown because no baseline is exposed.
              </p>
            </Panel>
          </div>
          <div className="report-grid report-breakdowns">
            {[
              ["Exceptions by category", categories],
              ["Exceptions by severity", severities],
              ["Exceptions by status", statuses],
            ].map(([title, values]) => {
              const entries = values as [string, number][];
              const max = Math.max(1, ...entries.map(([, value]) => value));
              return (
                <Panel
                  key={title as string}
                  title={title as string}
                  subtitle="Filtered case records"
                >
                  {entries.length ? (
                    <div className="report-bars">
                      {entries.map(([name, value], index) => (
                        <div className="report-bar-row" key={name}>
                          <span>{label(name)}</span>
                          <div className="report-bar-track">
                            <i
                              style={{
                                width: `${Math.max(4, (value / max) * 100)}%`,
                                background:
                                  chartColors[index % chartColors.length],
                              }}
                            />
                          </div>
                          <strong>{formatNumber(value)}</strong>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <Empty
                      title="No matching records"
                      description="Change the filters to include more cases."
                    />
                  )}
                </Panel>
              );
            })}
          </div>
        </>
      )}
    </>
  );
}
