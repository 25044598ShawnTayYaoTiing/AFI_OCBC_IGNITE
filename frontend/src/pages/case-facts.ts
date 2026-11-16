import type { Detail, Submission } from "../document-types";

export function nextCaseAction(submission: Submission, openCount: number) {
  if (submission.status === "OPERATIONS") return "Operations handover recorded";
  if (submission.status === "DRAFT") return "Upload the customer’s account-opening documents";
  if (submission.validated_revision !== submission.revision) return "Check the current document package";
  if (openCount && submission.next_action) return submission.next_action;
  if (openCount) return submission.status === "HUMAN_REVIEW" ? "Reviewer must assess the unresolved findings" : "Obtain the recorded corrections and recheck the package";
  return submission.status === "READY" ? "Confirm readiness for Operations" : "Review the recorded validation result";
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
