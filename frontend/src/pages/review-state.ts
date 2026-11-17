import type { Detail } from "../document-types";

// Presentation guards only. Every decision is revalidated by the existing API.
export function reviewState(detail: Detail) {
  const { submission: c, checks } = detail;
  const current = c.validated_revision === c.revision && checks.length > 0;
  const open = detail.exceptions.filter((issue) => issue.status === "OPEN");
  const failed = checks.filter(
    (check) =>
      !check.passed &&
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
  const held = !!c.risk_hold || c.status === "ON_HOLD";
  const eligible =
    !routed &&
    !held &&
    current &&
    failed.length === 0 &&
    open.length === 0 &&
    ["READY", "RESOLUTION_MANAGEMENT"].includes(c.status);
  const waiting = detail.tasks.filter((task) => task.status !== "RESOLVED");
  const customerTasks = waiting.filter(
    (task) => task.assigned_role === "CUSTOMER",
  );
  const authorisation = waiting.some(
    (task) => task.status === "AWAITING_RESOLVER_AUTHORIZATION",
  );
  const title = routed
    ? "Handed over to Operations"
    : held
      ? "On hold — reviewer investigation required"
      : !current
        ? "Recheck this revision before deciding"
        : blocking
          ? `Cannot approve — ${blocking} ${blocking === 1 ? "issue requires" : "issues require"} attention`
          : open.length
            ? "Review outstanding findings before deciding"
            : eligible
              ? "Ready for reviewer approval"
              : "Review the recorded validation outcome";
  const next = routed
    ? "The handover is recorded. Banking fulfilment takes place outside this workspace."
    : held
      ? "Review the evidence, resolve critical findings, then record an explicit hold-clearance decision."
      : !current
        ? "Run Recheck package to obtain current policy results. Earlier findings may be out of date."
        : authorisation
          ? "Review the findings and authorise customer correction when appropriate."
          : customerTasks.length
            ? "The customer has outstanding correction tasks. Review the requirements and supporting evidence."
            : open.length
              ? open[0].corrective_action
              : eligible
                ? "Review the evidence, then confirm approval and handover for this revision."
                : "Inspect readiness for the latest backend decision.";
  return {
    current,
    open,
    failed,
    blocking,
    routed,
    held,
    eligible,
    customerTasks,
    authorisation,
    title,
    next,
  };
}
