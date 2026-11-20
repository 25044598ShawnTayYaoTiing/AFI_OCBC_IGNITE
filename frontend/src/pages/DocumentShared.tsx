import { Fragment, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowRight, Download } from "lucide-react";
import { dateKey, dateTime, detailText, label, privatePdf } from "../api";
import type { Audit } from "../api";
import type { Detail, Metrics, Preflight, Submission } from "../document-types";
import { Badge, Empty, ErrorBox, Loading, Notice } from "../components";
import { nextCaseAction } from "./case-facts";
import { Disclosure } from "../ui";

export const BASE = "/api/documents";
export const message = (error: unknown) =>
  error instanceof Error
    ? error.message
    : "Please try again. If the problem continues, check the service connection.";
export const submissionNav = [
  { to: "/submissions", label: "All cases" },
  { to: "/investigations", label: "Investigations" },
  { to: "/corrections", label: "Corrections" },
  { to: "/preflight", label: "Handover readiness" },
];
export const reportNav = [
  { to: "/reports", label: "Analytics" },
  { to: "/audit", label: "Audit history" },
];
export function provenance(method?: string) {
  return method === "DIGITAL_EFORM"
    ? "Customer digital declaration"
    : method === "GEMINI"
      ? "Live Gemini"
      : method === "HUMAN_REVIEW"
        ? "Human review required"
        : method === "DETERMINISTIC_FALLBACK"
          ? "Rule-based fallback"
          : method === "SYNTHETIC_TEMPLATE"
            ? "Template extraction"
            : label(method);
}

export function SubmissionTable({
  items,
  tab = "overview",
  filtered = false,
}: {
  items: Submission[];
  tab?: string;
  filtered?: boolean;
}) {
  if (!items.length)
    return (
      <Empty
        title={filtered ? "No matching submissions" : "No submissions to show"}
        description={
          filtered
            ? "Try another search or clear the status filter."
            : "Customer requests will appear here after they are submitted."
        }
      />
    );
  return (
    <div className="table-scroll">
      <table className="submission-table demo-submission-table">
        <thead>
          <tr>
            <th scope="col">Customer</th>
            <th scope="col">Request type</th>
            <th scope="col">Status</th>
            <th scope="col">Risk &amp; checks</th>
            <th scope="col">Next action</th>
            <th scope="col">Submitted</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const currentValidated =
              item.validated_revision === item.revision && !!item.checks_total;
            return (
              <tr key={item.id}>
                <td>
                  <Link
                    className="record-link"
                    to={"/submissions/" + item.id + "?tab=" + tab}
                  >
                    {item.customer_name} <ArrowRight size={14} />
                  </Link>
                  <small className="secondary-line mono">
                    {item.customer_reference}
                  </small>
                </td>
                <td>
                  <span className="priority-service-tag">
                    {label(item.category)}
                  </span>
                  <small className="secondary-line">
                    {item.submission_source === "CUSTOMER_PORTAL"
                      ? item.capture_method === "DIGITAL_EFORM"
                        ? "Digital declaration"
                        : "Customer upload"
                      : "Staff upload"}
                  </small>
                </td>
                <td>
                  <div className="status-cell-badges">
                    <Badge value={item.status} text={item.staff_suspended ? "Suspended" :
                      item.status === "OPERATIONS" ? "Completed" : undefined} />
                    {item.risk_hold && item.status !== "ON_HOLD" && <Badge value="ON_HOLD" text="On hold" />}
                  </div>
                </td>
                <td>
                  {item.risk_level && item.risk_score != null ? (
                    <span
                      className={`inline-risk-tag risk-tag-${item.risk_level.toLowerCase()}`}
                    >
                      {label(item.risk_level)} · {item.risk_score}/100
                    </span>
                  ) : (
                    <span className="muted small">Not assessed</span>
                  )}
                  {currentValidated && (
                    <small className="secondary-line">
                      {item.checks_passed ?? 0}/{item.checks_total} checks
                      passed
                    </small>
                  )}
                </td>
                <td>
                  <strong className="table-next-action">
                    {nextCaseAction(item, item.open_exceptions ?? 0)}
                  </strong>
                  {currentValidated && !["OPERATIONS", "CANCELLED"].includes(item.status) &&
                    <small className="secondary-line">
                    {item.open_exceptions
                      ? `${item.open_exceptions} open ${item.open_exceptions === 1 ? "issue" : "issues"}`
                      : "No open issues"}
                  </small>}
                </td>
                <td className="nowrap small muted">
                  {dateTime(item.created_at)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

const statusToneMap: Record<string, string> = {
  OPERATIONS: "success",
  READY: "success",
  RESOLUTION_MANAGEMENT: "info",
  HUMAN_REVIEW: "info",
  PENDING_VALIDATION: "warning",
  CORRECTION_REQUIRED: "warning",
  ON_HOLD: "danger",
  DRAFT: "neutral",
  CANCELLED: "neutral",
};

export function StatusChart({ metrics }: { metrics: Metrics }) {
  const entries = Object.entries(metrics.statuses)
    .filter(([, value]) => value > 0)
    .sort((a, b) => b[1] - a[1]);
  const total = Math.max(
    1,
    entries.reduce((sum, [, value]) => sum + value, 0),
  );
  const maximum = Math.max(1, ...entries.map(([, value]) => value));
  return (
    <div
      className="status-chart"
      aria-label="Current submission status distribution"
    >
      <div className="chart-header">
        <h3>Submission status breakdown</h3>
        <span>{metrics.totalSubmissions} total</span>
      </div>
      {entries.map(([status, count]) => {
        const tone = statusToneMap[status] || "info";
        const pct = Math.round((count / total) * 100);
        return (
          <div key={status} className="chart-row">
            <span className="chart-label">
              <i className={`chart-dot tone-${tone}`} aria-hidden="true" />
              {status === "OPERATIONS" ? "Completed" : label(status)}
            </span>
            <div className="chart-track" aria-hidden="true">
              <div
                className={`chart-fill tone-${tone}`}
                style={{ width: `${Math.max(6, (count / maximum) * 100)}%` }}
              />
            </div>
            <strong className="chart-value">
              {count} <small>({pct}%)</small>
            </strong>
          </div>
        );
      })}
    </div>
  );
}

export function HorizontalBarChart({
  items,
  tone = "accent",
  ariaLabel,
}: {
  items: { label: string; value: number; tone?: string }[];
  tone?: string;
  ariaLabel: string;
}) {
  const maximum = Math.max(1, ...items.map((item) => item.value));
  const total = Math.max(
    1,
    items.reduce((sum, item) => sum + item.value, 0),
  );
  return (
    <div className="status-chart" aria-label={ariaLabel}>
      {items.map((item) => {
        const barTone = item.tone || tone;
        const pct = Math.round((item.value / total) * 100);
        return (
          <div key={item.label} className="chart-row">
            <span className="chart-label" title={item.label}>
              {item.label}
            </span>
            <div className="chart-track" aria-hidden="true">
              <div
                className={`chart-fill tone-${barTone}`}
                style={{
                  width: `${item.value > 0 ? Math.max(6, (item.value / maximum) * 100) : 0}%`,
                }}
              />
            </div>
            <strong className="chart-value">
              {item.value} <small>({pct}%)</small>
            </strong>
          </div>
        );
      })}
    </div>
  );
}

export function PdfPreview({
  caseId,
  versionId,
  page = 1,
  pageCount = 1,
  convertedFromImages = false,
}: {
  caseId: string;
  versionId: string;
  page?: number;
  pageCount?: number;
  convertedFromImages?: boolean;
}) {
  const [url, setUrl] = useState("");
  const [mime, setMime] = useState("application/pdf");
  const [imagePage, setImagePage] = useState(page);
  useEffect(() => { setImagePage(Math.max(1, Math.min(page, pageCount))); }, [page, pageCount, versionId]);
  const [error, setError] = useState("");
  const [mobilePreview, setMobilePreview] = useState(false);
  useEffect(() => {
    let active = true;
    let objectUrl = "";
    setUrl("");
    setError("");
    setMobilePreview(false);
    privatePdf(`${BASE}/submissions/${caseId}/versions/${versionId}/pdf?page=${imagePage}`)
      .then((blob) => {
        if (active) setMime(blob.type);
        objectUrl = URL.createObjectURL(blob);
        if (active) setUrl(objectUrl);
        else URL.revokeObjectURL(objectUrl);
      })
      .catch((error) => {
        if (active) setError(message(error));
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [caseId, versionId, imagePage]);
  return error ? (
    <ErrorBox message={error} />
  ) : url ? (
    <>
      {convertedFromImages && <Notice><strong>Converted from customer-uploaded images</strong><p>Original images are retained securely for audit. Conversion does not verify authenticity; compare the visible information with the extracted fields.</p></Notice>}
      {mime.startsWith('image/') && <><Notice>Unverified image evidence. Compare the original with extracted fields; AI extraction does not establish authenticity.</Notice>{pageCount > 1 && <label>Image page <select aria-label="Image page" value={imagePage} onChange={event => setImagePage(Number(event.target.value))}>{Array.from({ length: pageCount }, (_, index) => <option key={index} value={index + 1}>Page {index + 1}</option>)}</select></label>}</>}
      <div className="mobile-pdf-controls">
        <p>
          Requested source: page {page}. Open the full document for a larger view, or
          preview it here.
        </p>
        <button
          className="button secondary"
          aria-expanded={mobilePreview}
          onClick={() => setMobilePreview((value) => !value)}
        >
          {mobilePreview ? "Hide preview" : "Preview here"}
        </button>
      </div>
      {mime.startsWith("image/") ? <img className="document-image-preview" src={url} alt={`Original uploaded image, page ${imagePage}`} /> : <iframe
        className={`document-preview ${mobilePreview ? "mobile-pdf-open" : ""}`}
        key={`${versionId}:${page}`}
        title={`${convertedFromImages ? 'Converted PDF' : 'Original PDF document'}, page ${page}`}
        src={`${url}#page=${page}&view=FitH`}
      />}
      <div className="preview-footer">
        <span>{convertedFromImages ? 'Converted PDF' : 'Original document'} | Requested page {page}</span>
        <a
          className="text-link"
          href={`${url}#page=${page}`}
          target="_blank"
          rel="noreferrer"
        >
          {convertedFromImages ? 'Open full PDF' : 'Open original document'}
        </a>
        <a className="text-link" href={url} download={`document.${mime === "image/png" ? "png" : mime === "image/jpeg" ? "jpg" : "pdf"}`}>
          <Download size={15} />
          {convertedFromImages ? 'Download PDF' : 'Download original'}
        </a>
      </div>
    </>
  ) : (
    <Loading text="Opening document..." />
  );
}

const eventNames: Record<string, string> = {
  EXCEPTION_DETECTED: "Issue detected",
  AI_INVESTIGATION: "Investigation completed",
  RESOLUTION_PLANNED: "Attachment planned",
  PREFLIGHT_PASSED: "Safety check passed",
  RISK_ASSESSED: "Risk assessed",
  EFORM_SUBMITTED: "Customer submitted request",
  EFORM_UPDATED: "Customer updated request",
  DECLARATION_CAPTURED: "Request details saved",
  CANCELLED: "Customer cancelled request",
  STAFF_CANCELLED: "Resolution Management cancelled the case",
  STAFF_SUSPENDED: "Resolution Management suspended processing",
  HOLD_CLEARED: "Resolution Management lifted the hold",
  CUSTOMER_CORRECTION_AUTHORIZED: "Correction requested from customer",
  AUTOMATICALLY_APPROVED: "Case approved automatically",
  RESOLUTION_MANAGEMENT_APPROVED: "Staff approval recorded",
  POLICY_APPROVED: "Automatic action approved",
  ACTION_EXECUTED: "Supporting document attached",
  OUTCOME_VERIFIED: "Attachment verified",
  EXCEPTION_RESOLVED: "Issue resolved",
  AUTOMATION_COMPLETED: "Automatic resolution complete",
  AUTOMATION_BLOCKED: "Automatic action blocked",
  AUTOMATION_FAILED: "Automatic action failed",
  SERVICE_REQUEST_EXECUTED: "Approved service request completed",
  ROUTED: "Completed",
  UPLOADED: "Document uploaded",
  REPLACED: "Document replaced",
  VALIDATED: "Documents checked",
  PREFLIGHT: "Readiness checked",
  CREATED: "Request created",
  ADMINISTRATIVE_ATTACHMENT: "Authorised repository document attached",
};
function parseDetails(raw: unknown): Record<string, unknown> | null {
  if (!raw) return null;
  if (typeof raw === "object" && !Array.isArray(raw))
    return raw as Record<string, unknown>;
  if (typeof raw === "string") {
    const trimmed = raw.trim();
    if (trimmed.startsWith("{")) {
      try {
        return JSON.parse(trimmed) as Record<string, unknown>;
      } catch {
        return null;
      }
    }
  }
  return null;
}

function formatActor(actor: string) {
  const lower = actor.toLowerCase();
  if (lower === "reviewer" || lower === "rules-admin")
    return `Resolution Management (${actor})`;
  if (lower.startsWith("user-") || lower.startsWith("customer"))
    return "Customer Portal";
  return label(actor);
}

function timelineTone(key: string) {
  if (
    /VERIFIED|RESOLVED|COMPLETED|ROUTED|APPROVED|PASSED|CLEARED/.test(key)
  )
    return "success";
  if (/BLOCKED|FAILED|DETECTED|CANCELLED/.test(key)) return "danger";
  if (/PLANNED|HOLD|ESCALATED/.test(key)) return "warning";
  return "info";
}

function humanAuditSummary(key: string, details: Record<string, unknown> | null) {
  if (!details) return "";
  const parts: string[] = [];
  if (typeof details.note === "string" && details.note)
    parts.push(`"${details.note}"`);
  if (typeof details.verificationNote === "string" && details.verificationNote)
    parts.push(`"${details.verificationNote}"`);
  if (typeof details.reason === "string" && details.reason)
    parts.push(String(details.reason));
  if (typeof details.verification === "string" && details.verification)
    parts.push(String(details.verification));
  if (typeof details.checks === "number") {
    const failed =
      typeof details.failed === "number" ? `, ${details.failed} failed` : "";
    parts.push(`${details.checks} checks evaluated${failed}`);
  }
  if (typeof details.approvedChecks === "number")
    parts.push(`${details.approvedChecks} checks verified`);
  if (typeof details.findings === "number")
    parts.push(`${details.findings} finding(s) analysed`);
  if (typeof details.level === "string" && typeof details.score === "number")
    parts.push(`${label(details.level)} risk (${details.score}/100)`);
  if (typeof details.method === "string" && !key.includes("RISK"))
    parts.push(provenance(String(details.method)));
  if (typeof details.revision === "number")
    parts.push(`Revision ${details.revision}`);
  return parts.join(" · ");
}

export function AuditList({
  items,
  compact = false,
}: {
  items: Audit[];
  compact?: boolean;
}) {
  return items.length ? (
    <ol className={`activity-timeline ${compact ? "compact-timeline" : ""}`}>
      {items.map((event, index) => {
        const key = event.eventType.replace("DOCUMENT_", "");
        const [date, time] = dateTime(event.timestamp).split(", ");
        const startsDay =
          index === 0 ||
          dateKey(items[index - 1].timestamp) !== dateKey(event.timestamp);
        const parsed = parseDetails(event.details);
        const summary = humanAuditSummary(key, parsed);
        const tone = timelineTone(key);
        return (
          <Fragment key={event.id}>
            {startsDay && !compact && (
              <li className="audit-day">
                <strong>{date} SGT</strong>
              </li>
            )}
            <li className="timeline-entry">
              <span className={`timeline-dot tone-${tone}`} />
              <div className="activity-content">
                <div className="activity-heading">
                  <strong>{eventNames[key] || label(key)}</strong>
                  <time dateTime={event.timestamp}>
                    {compact ? dateTime(event.timestamp) + " SGT" : time || date}
                  </time>
                </div>
                <div className="activity-subline">
                  <span className="activity-actor">
                    {formatActor(event.actor)}
                  </span>
                  {summary && (
                    <span className="activity-summary">{summary}</span>
                  )}
                </div>
                {!compact && (
                  <Disclosure title="Advanced details">
                    <p className="preserve-lines small mono">
                      {detailText(event.details)}
                    </p>
                    <dl className="metadata">
                      <div>
                        <dt>Event ID</dt>
                        <dd className="mono">{event.id}</dd>
                      </div>
                      <div>
                        <dt>Actor</dt>
                        <dd>{event.actor}</dd>
                      </div>
                      {event.exceptionId && (
                        <div>
                          <dt>Case reference</dt>
                          <dd className="mono">{event.exceptionId}</dd>
                        </div>
                      )}
                      {event.correlationId && (
                        <div>
                          <dt>Reference</dt>
                          <dd className="mono">{event.correlationId}</dd>
                        </div>
                      )}
                    </dl>
                  </Disclosure>
                )}
              </div>
            </li>
          </Fragment>
        );
      })}
    </ol>
  ) : (
    <Empty
      title="No activity yet"
      description="Recorded actions will appear here."
    />
  );
}

export function Checklist({
  checks,
}: {
  checks: Detail["checks"] | Preflight["checks"];
}) {
  return (
    <ul className="validation-list">
      {checks.map((check, index) => (
        <li key={check.id || index} className="validation-list-item">
          <div className="validation-list-main">
            <strong>{check.name}</strong>
            {!check.passed && <p>{check.corrective_action}</p>}
          </div>
          <Badge value={check.passed ? "PASSED" : "FAILED"} />
        </li>
      ))}
    </ul>
  );
}
