import { useState } from "react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  AlertCircle,
  ArrowUpRight,
  Check,
  ChevronRight,
  Circle,
  Clock3,
  Inbox,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
} from "lucide-react";
import { dateTime, detailText, label } from "./api";
import type { Audit, Case } from "./api";
export function Badge({ value }: { value: string }) {
  return (
    <span className={`badge badge-${value.toLowerCase()}`}>
      <span />
      {label(value)}
    </span>
  );
}
export function PageHeader({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow?: string;
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="header-actions">{children}</div>
    </header>
  );
}
export function Panel({
  title,
  subtitle,
  action,
  children,
  className = "",
}: {
  title?: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {title && (
        <div className="panel-header">
          <div>
            <h2>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Loading({
  text = "Loading workspace data…",
}: {
  text?: string;
}) {
  return (
    <div className="loading" role="status">
      <LoaderCircle className="spin" size={24} />
      <span>{text}</span>
    </div>
  );
}
export function Empty({
  title = "Nothing to show yet",
  description = "Records will appear here as your workflow progresses.",
  children,
}: {
  title?: string;
  description?: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Inbox size={26} />
      </span>
      <h3>{title}</h3>
      <p>{description}</p>
      {children}
    </div>
  );
}
export function ErrorBox({
  message,
  retry,
}: {
  message: string;
  retry?: () => void;
}) {
  return (
    <div className="error-box" role="alert">
      <AlertCircle size={18} />
      <span>{message}</span>
      {retry && (
        <button className="text-button" onClick={retry}>
          <RefreshCw size={14} />
          Retry
        </button>
      )}
    </div>
  );
}
export function Notice({
  children,
  success = false,
}: {
  children: ReactNode;
  success?: boolean;
}) {
  return (
    <div className={`notice ${success ? "notice-success" : ""}`}>
      {success ? <ShieldCheck size={18} /> : <AlertCircle size={18} />}
      <div>{children}</div>
    </div>
  );
}
export function useOperation() {
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function run(name: string, fn: () => Promise<void>) {
    setBusy(name);
    setError("");
    setMessage("");
    try {
      await fn();
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Unable to complete this action",
      );
    } finally {
      setBusy("");
    }
  }
  return { busy, error, message, setMessage, run };
}
export function OperationStatus({
  error,
  message,
}: {
  error: string;
  message: string;
}) {
  return (
    <>
      {error && <ErrorBox message={error} />}{" "}
      {message && <Notice success>{message}</Notice>}
    </>
  );
}
export function Workflow({ status }: { status: string }) {
  const stages = [
    "Detected",
    "Investigated",
    "Simulated",
    "Executed",
    "Verified",
  ];
  const index =
    status === "RESOLVED"
      ? 5
      : ["VERIFYING", "VERIFICATION_FAILED", "EXECUTING"].includes(status)
        ? 3
        : ["SIMULATED", "READY_TO_RESOLVE", "READY_FOR_RESOLUTION"].includes(
              status,
            )
          ? 2
          : [
                "INVESTIGATED",
                "AWAITING_APPROVAL",
                "APPROVAL_REQUIRED",
                "MANUAL_REVIEW",
                "REJECTED",
              ].includes(status)
            ? 1
            : 0;
  return (
    <div className="workflow">
      {stages.map((stage, i) => (
        <div
          className={`workflow-stage ${i < index ? "complete" : ""} ${i === index ? "current" : ""}`}
          key={stage}
        >
          <span className="workflow-dot">
            {i < index ? <Check size={14} /> : i + 1}
          </span>
          <span>{stage}</span>
          {i < stages.length - 1 && <span className="workflow-connector" />}
        </div>
      ))}
    </div>
  );
}
export function CaseTable({
  items,
  compact = false,
}: {
  items: Case[];
  compact?: boolean;
}) {
  if (!items.length)
    return (
      <Empty
        title="No exceptions found"
        description="Try a different filter or run exception detection."
      />
    );
  return (
    <div className="table-scroll">
      <table className="case-table">
        <thead>
          <tr>
            <th>Exception / description</th>
            <th>Batch</th>
            <th>Severity</th>
            <th>Status</th>
            {!compact && <th>Workflow</th>}
            <th>Detected · SGT</th>
            <th>
              <span className="sr-only">Open exception</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <td>
                <Link className="case-title" to={`/legacy/exceptions/${item.id}`}>
                  {item.id}
                  <ArrowUpRight size={13} />
                </Link>
                <span className="case-subtitle">{label(item.type)}</span>
                {!compact && (
                  <span className="case-description">{item.description}</span>
                )}
              </td>
              <td className="mono small">{item.batchId || "—"}</td>
              <td>
                <Badge value={item.severity} />
              </td>
              <td>
                <Badge value={item.status} />
              </td>
              {!compact && (
                <td className="small muted">{label(item.workflow)}</td>
              )}
              <td className="small muted nowrap">
                {dateTime(item.detectedAt)}
              </td>
              <td>
                <Link
                  className="icon-button"
                  to={`/legacy/exceptions/${item.id}`}
                  aria-label={`Open ${item.id}`}
                >
                  <ChevronRight size={17} />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function Timeline({
  events,
  compact = false,
}: {
  events: Audit[];
  compact?: boolean;
}) {
  if (!events.length)
    return (
      <Empty
        title="Your audit trail starts here"
        description="Actions and decisions will appear as cases progress."
      />
    );
  return (
    <div className={`timeline ${compact ? "compact-timeline" : ""}`}>
      {events.map((event) => (
        <article className="timeline-item" key={event.id}>
          <span
            className={`timeline-marker ${/RESOLVED|VERIFIED|COMPLETED|SUCCESS/.test(event.eventType) ? "positive" : /REJECT|FAIL/.test(event.eventType) ? "negative" : ""}`}
          >
            {/RESOLVED|VERIFIED|COMPLETED|SUCCESS/.test(event.eventType) ? (
              <Check size={13} />
            ) : (
              <Circle size={8} />
            )}
          </span>
          <div className="timeline-body">
            <div className="timeline-heading">
              <h3>{label(event.eventType)}</h3>
              <time>{dateTime(event.timestamp)}</time>
            </div>
            {event.exceptionId && (
              <Link
                to={`/legacy/exceptions/${event.exceptionId}`}
                className="timeline-case"
              >
                {event.exceptionId}
              </Link>
            )}
            <p>{detailText(event.details)}</p>
            <div className="timeline-meta">
              <span>{event.actor}</span>
              <span>{label(event.result)}</span>
              {!compact && <span className="mono">{event.id}</span>}
            </div>
            {!compact && event.correlationId && (
              <span className="correlation">
                Correlation: {event.correlationId}
              </span>
            )}
          </div>
        </article>
      ))}
    </div>
  );
}
export function SelectCase({
  items,
  selected,
  onChange,
}: {
  items: Case[];
  selected: string;
  onChange: (id: string) => void;
}) {
  return (
    <label className="case-selector">
      <span>Exception</span>
      <select
        value={selected}
        onChange={(e) => onChange(e.target.value)}
        aria-label="Select exception"
      >
        <option value="" disabled>
          Choose an exception
        </option>
        {items.map((c) => (
          <option key={c.id} value={c.id}>
            {c.id} · {label(c.type)} · {label(c.status)}
          </option>
        ))}
      </select>
    </label>
  );
}
export function SearchBox({
  value,
  onChange,
  placeholder = "Search exceptions…",
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <div className="search-input">
      <Search size={17} />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
      />
    </div>
  );
}
export function TimeTag({ value }: { value: string }) {
  return (
    <span className="time-tag">
      <Clock3 size={13} />
      {dateTime(value)} SGT
    </span>
  );
}
