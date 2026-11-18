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
        <thead><tr>
          <th scope="col">Customer</th><th scope="col">Request</th>
          <th scope="col">Status</th><th scope="col">Risk</th><th scope="col">Next action</th>
        </tr></thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <td><Link className="record-link" to={"/submissions/" + item.id + "?tab=" + tab}>{item.customer_name} <ArrowRight size={14} /></Link></td>
              <td>{label(item.category)}</td>
              <td><Badge value={item.status} /></td>
              <td>{item.risk_level && item.risk_score != null ? label(item.risk_level) + " · " + item.risk_score + "/100" : "Not assessed"}</td>
              <td>{nextCaseAction(item, item.open_exceptions ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function StatusChart({ metrics }: { metrics: Metrics }) {
  const entries = Object.entries(metrics.statuses).filter(
    ([, value]) => value > 0,
  );
  const maximum = Math.max(1, ...entries.map(([, value]) => value));
  return (
    <div
      className="status-chart"
      aria-label="Current submission status distribution"
    >
      <h3>Submission distribution</h3>
      {entries.map(([status, count]) => (
        <div key={status} className="chart-row">
          <span>
            {status === "OPERATIONS" ? "With Operations" : label(status)}
          </span>
          <div className="chart-track" aria-hidden="true">
            <div style={{ width: `${(count / maximum) * 100}%` }} />
          </div>
          <strong>{count}</strong>
        </div>
      ))}
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
  CUSTOMER_CORRECTION_AUTHORIZED: "Customer correction requested",
  RESOLUTION_MANAGEMENT_APPROVED: "Staff approval recorded",
  POLICY_APPROVED: "Automatic action approved",
  ACTION_EXECUTED: "Supporting document attached",
  OUTCOME_VERIFIED: "Attachment verified",
  EXCEPTION_RESOLVED: "Issue resolved",
  AUTOMATION_COMPLETED: "Automatic resolution complete",
  AUTOMATION_BLOCKED: "Automatic action blocked",
  AUTOMATION_FAILED: "Automatic action failed",
  ROUTED: "Sent to Operations",
  UPLOADED: "Document uploaded",
  REPLACED: "Document replaced",
  VALIDATED: "Documents checked",
  PREFLIGHT: "Readiness checked",
  CREATED: "Request created",
  ADMINISTRATIVE_ATTACHMENT: "Authorised repository document attached",
};
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
        const startsDay = index === 0 || dateKey(items[index - 1].timestamp) !== dateKey(event.timestamp);
        return (
          <Fragment key={event.id}>
          {startsDay && !compact && <li className="audit-day"><strong>{date} SGT</strong></li>}
          <li>
            <span className="timeline-dot" />
            <div className="activity-content">
              <div className="activity-heading">
                <strong>{eventNames[key] || label(key)}</strong>
                <time dateTime={event.timestamp}>{compact ? dateTime(event.timestamp) + " SGT" : time || date}</time>
              </div>

              {!compact && (
                <Disclosure title="Advanced details">
                  <p className="preserve-lines">{detailText(event.details)}</p>
                  <dl className="metadata">
                    <div>
                      <dt>Event ID</dt>
                      <dd>{event.id}</dd>
                    </div>
                    <div><dt>Actor</dt><dd>{event.actor}</dd></div>
                    {event.correlationId && (
                      <div>
                        <dt>Reference</dt>
                        <dd>{event.correlationId}</dd>
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
        <li key={check.id || index}>
          <span>
            <strong>{check.name}</strong>
            {!check.passed && <small>{check.corrective_action}</small>}
          </span>
          <Badge value={check.passed ? "PASSED" : "FAILED"} />
        </li>
      ))}
    </ul>
  );
}
