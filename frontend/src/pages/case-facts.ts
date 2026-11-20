import type { Detail, Submission } from "../document-types";

export function nextCaseAction(submission: Submission, openCount: number) {
  if (submission.status === "OPERATIONS") return "Completed";
  if (submission.status === "CANCELLED") return "No action needed";
  if (submission.status === "DRAFT") return "Add documents";
  if (submission.risk_hold || submission.status === "ON_HOLD") return "Review the risk and evidence";
  if (submission.validated_revision !== submission.revision) return "Check the request";
  if (submission.status === "CORRECTION_REQUIRED") return "Wait for customer corrections";
  if (openCount) return "Review open issues";
  return ["READY", "RESOLUTION_MANAGEMENT"].includes(submission.status)
    ? "Review for approval" : "View case outcome";
}

export function caseFacts(detail: Detail) {
  const open = detail.exceptions.filter(item => item.status === "OPEN");
  const severities = open.map(item => item.severity);
  const highest = ["CRITICAL", "HIGH", "BLOCKING", "MEDIUM", "WARNING", "LOW", "INFO"].find(value => severities.includes(value));
  const current = detail.submission.validated_revision === detail.submission.revision;
  return {
    open, current,
    passed: detail.checks.filter(check => check.passed).length,
    uncertain: detail.documents.some(doc => doc.extraction.requires_review),
    severity: highest ? highest.charAt(0) + highest.slice(1).toLowerCase() : open.length ? "Not specified" : current ? "None recorded" : "Not assessed",
    owners: [...new Set(detail.tasks.filter(task => task.status !== "RESOLVED" && open.some(issue => issue.id === task.exception_id)).map(task => task.assigned_role.replaceAll("_", " ").toLowerCase()))],
    ready: ["READY", "OPERATIONS"].includes(detail.submission.status),
    routed: detail.submission.status === "OPERATIONS",
  };
}


export const requestedFields: Record<string, string[]> = {
  ADDRESS_CHANGE: ["mailing_address"],
  RESIDENTIAL_ADDRESS_CHANGE: ["residential_address"],
  CONTACT_UPDATE: ["email"],
  TAX_RESIDENCY_UPDATE: ["tax_residency", "foreign_tax_country", "tax_identifier"],
  ADDITIONAL_ACCOUNT: ["account_type", "currency", "source_of_funds"],
};
