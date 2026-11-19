import type { Detail } from "../document-types";

// Presentation guards only. Every decision is revalidated by the existing API.
export function reviewState(detail: Detail) {
  const { submission: c, checks } = detail;
  const current = c.validated_revision === c.revision && checks.length > 0;
  const open = detail.exceptions.filter((issue) => issue.status === "OPEN");
  const pendingVerification =
    c.status === "PENDING_VALIDATION" ||
    checks.some((check) => check.outcome === "UNAVAILABLE") ||
    detail.documents.some(
      (doc) =>
        doc.extraction.decision?.outcome === "UNAVAILABLE" ||
        Boolean(doc.extraction.decision?.unavailable),
    );
  const failed = checks.filter(
    (check) =>
      !check.passed &&
      check.outcome !== "UNAVAILABLE" &&
      (check.severity === "BLOCKING" ||
        check.condition_type === "CLASSIFICATION"),
  );
  const blocking = current
    ? Math.max(
        failed.length,
        open.filter((issue) => issue.severity === "BLOCKING").length,
      )
    : null;
  const routed = c.status === "OPERATIONS";
  const cancelled = c.status === "CANCELLED";
  const held = !!c.risk_hold || c.status === "ON_HOLD";
  const eligible =
    !routed &&
    !cancelled &&
    !held &&
    !pendingVerification &&
    current &&
    failed.length === 0 &&
    open.length === 0 &&
    ["READY", "RESOLUTION_MANAGEMENT"].includes(c.status);
  const waiting = detail.tasks.filter(
    (task) => !["RESOLVED", "CANCELLED"].includes(task.status),
  );
  const customerTasks = waiting.filter(
    (task) => task.assigned_role === "CUSTOMER",
  );
  const authorisation = waiting.some(
    (task) => task.status === "AWAITING_RESOLVER_AUTHORIZATION",
  );
  const title = cancelled ? "Request cancelled"
    : routed ? "Sent to Operations"
    : held ? "Needs review"
    : pendingVerification ? "Verification pending"
    : !current ? "Request needs checking"
    : authorisation ? "Staff approval required for corrections"
    : customerTasks.length ? "Waiting for customer corrections"
    : open.length || blocking ? "Issues need attention"
    : eligible ? "Ready for Operations approval"
    : "Request checked";
  const next = cancelled ? "No further action is needed."
    : routed ? "Operations will take it from here."
    : held ? "Review the evidence and risk before clearing the hold."
    : pendingVerification ? "Automated verification is temporarily delayed. Recheck the request shortly; submitted files remain safely stored."
    : !current ? "Check the latest request details before making a decision."
    : authorisation ? "Review the issues before asking the customer for a correction."
    : customerTasks.length ? "The customer needs to update their request before it can proceed."
    : open.length ? "Review the issue and its supporting evidence below."
    : eligible ? "Approve this request to send it to Operations."
    : "Review the recorded outcome before taking further action.";
  return {
    current,
    open,
    failed,
    blocking,
    routed,
    cancelled,
    held,
    pendingVerification,
    eligible,
    customerTasks,
    authorisation,
    title,
    next,
  };
}
