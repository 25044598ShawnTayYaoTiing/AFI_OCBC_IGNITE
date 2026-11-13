import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowRight, Download } from "lucide-react";
import { dateTime, detailText, label, privatePdf } from "../api";
import type { Audit } from "../api";
import type { Detail, Metrics, Preflight, Submission } from "../document-types";
import { Badge, Empty, ErrorBox, Loading } from "../components";
import { Disclosure } from "../ui";

export const BASE = "/api/documents";
export const message = (error: unknown) =>
  error instanceof Error
    ? error.message
    : "Please try again. If the problem continues, check the service connection.";
export const submissionNav = [
  { to: "/submissions", label: "All submissions" },
  { to: "/investigations", label: "Investigations" },
  { to: "/corrections", label: "Corrections" },
  { to: "/preflight", label: "Preflight" },
];
export const reportNav = [
  { to: "/reports", label: "Analytics" },
  { to: "/audit", label: "Audit history" },
];
export function provenance(method?: string) {
  return method === "DIGITAL_EFORM" ? "Customer digital declaration" : method === "GEMINI"
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
  tab = "review",
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
            : "Create a submission or open the demo environment to get started."
        }
      />
    );
  return (
    <div className="table-scroll">
      <table className="submission-table">
        <thead>
          <tr>
            <th scope="col">Customer</th>
            <th scope="col">Reference</th>
            <th scope="col">Source</th>
            <th scope="col">Status</th>
            <th scope="col">Resolution</th>
            <th scope="col">Created</th>
            <th scope="col">
              <span className="sr-only">Open submission</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <td>
                <Link
                  className="record-link"
                  to={`/submissions/${item.id}?tab=${tab}`}
                >
                  {item.customer_name}
                </Link>
                <small className="secondary-line">{label(item.category)} / {item.owner_name}</small>
              </td>
              <td>
                <span className="reference">{item.customer_reference}</span>
              </td>
              <td>{item.submission_source === "CUSTOMER_PORTAL" ? item.capture_method === "DIGITAL_EFORM" ? "Digital eForm" : "Customer PDF" : "Staff PDF archive"}</td>
              <td>
                <Badge value={item.status} />
              </td>
              <td>
                <span>
                  {item.open_exceptions
                    ? `${item.open_exceptions} open ${item.open_exceptions === 1 ? "issue" : "issues"}`
                    : item.status === "OPERATIONS"
                      ? "Intake complete"
                      : "No open findings"}
                </span>
                <small className="secondary-line">
                  {item.verified_automations
                    ? `${item.verified_automations} automatic action verified`
                    : item.ai_method
                      ? provenance(item.ai_method)
                      : "Awaiting analysis"}
                </small>
              </td>
              <td className="nowrap">{dateTime(item.created_at)}</td>
              <td>
                <Link
                  className="row-action"
                  aria-label={`Review ${item.customer_name}`}
                  to={`/submissions/${item.id}?tab=${tab}`}
                >
                  <ArrowRight size={16} />
                </Link>
              </td>
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
}: {
  caseId: string;
  versionId: string;
  page?: number;
}) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    let objectUrl = "";
    setUrl("");
    setError("");
    privatePdf(`${BASE}/submissions/${caseId}/versions/${versionId}/pdf`)
      .then((blob) => {
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
  }, [caseId, versionId]);
  return error ? (
    <ErrorBox message={error} />
  ) : url ? (
    <>
      <iframe
        className="document-preview"
        title="Original PDF document"
        src={`${url}#page=${page}&view=FitH`}
      />
      <div className="preview-footer">
        <span>Original document</span>
        <a className="text-link" href={url} download="synthetic-document.pdf">
          <Download size={15} />
          Download PDF
        </a>
      </div>
    </>
  ) : (
    <Loading text="Opening document..." />
  );
}

const eventNames: Record<string, string> = {
  EXCEPTION_DETECTED: "Issue detected",
  AI_INVESTIGATION: "AI investigation completed",
  RESOLUTION_PLANNED: "Attachment planned",
  PREFLIGHT_PASSED: "Preflight passed",
  POLICY_APPROVED: "Action authorised",
  ACTION_EXECUTED: "Supporting document attached",
  OUTCOME_VERIFIED: "Attachment verified",
  EXCEPTION_RESOLVED: "Issue resolved",
  AUTOMATION_COMPLETED: "Automatic resolution complete",
  AUTOMATION_BLOCKED: "Automatic action blocked",
  AUTOMATION_FAILED: "Automatic action failed",
  ROUTED: "Sent to Operations",
  UPLOADED: "Document uploaded",
  REPLACED: "Document replaced",
  VALIDATED: "Validation completed",
  PREFLIGHT: "Readiness checked",
  CREATED: "Submission created",
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
      {items.map((event) => {
        const key = event.eventType.replace("DOCUMENT_", "");
        return (
          <li key={event.id}>
            <span className="timeline-dot" />
            <div className="activity-content">
              <div className="activity-heading">
                <strong>{eventNames[key] || label(key)}</strong>
                <time>{dateTime(event.timestamp)} SGT</time>
              </div>
              <p>{event.actor}</p>
              {!compact && (
                <Disclosure title="Event details">
                  <p className="preserve-lines">{detailText(event.details)}</p>
                  <dl className="metadata">
                    <div>
                      <dt>Event</dt>
                      <dd>{event.id}</dd>
                    </div>
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
