import type { Finding } from "../document-types";

const referenceRules = new Set([
  "REF_ACCOUNT_APPLICATION",
  "REF_CUSTOMER_INFORMATION",
  "REF_TAX_RESIDENCY",
]);

export function issueRuleKey(issue: Finding) {
  return issue.check_key?.split(":", 1)[0] || "";
}

export function issueCategory(issue: Finding) {
  if (issue.status === "RESOLVED") {
    if (issue.resolution_method === "AUTOMATIC_VERIFIED_ATTACHMENT")
      return "Resolved automatically and verified";
    if (issue.resolution_method === "MANUAL_VERIFIED_ATTACHMENT")
      return "Resolved manually with verified attachment";
    if (issue.resolution_method === "ONEMAP_UNAVAILABLE_DEFERRED")
      return "Deferred while OneMap unavailable";
    return "Resolved after checking again";
  }
  return issue.action_type === "ATTACH_VERIFIED_DOCUMENT"
    ? "Eligible for automatic resolution"
    : "Needs a person to review";
}

export function issueTitle(issue: Finding) {
  const key = issueRuleKey(issue);
  if (key === "ATTACH_SUPPORTING_DOCUMENT")
    return issue.status === "RESOLVED"
      ? "Supporting document verified"
      : "Supporting document missing";
  if (key === "MATCH_MAILING") return "Mailing addresses differ";
  if (key === "TAX_DATE") return "Tax declaration needs a date";
  if (key === "SERVICE_SUPPORT")
    return issue.status === "RESOLVED"
      ? "Supporting evidence verified"
      : "Supporting evidence needs review";
  if (key === "PROFILE_IDENTITY")
    return "Identity must match the stored profile";
  if (key === "PROFILE_NAME")
    return "Customer name must match the stored profile";
  if (key === "CLASSIFICATION") return "Some document details need checking";
  return referenceRules.has(issueRuleKey(issue))
    ? "Customer reference differs"
    : issue.name;
}

export function groupFindings(findings: Finding[]) {
  const groups: { key: string; items: Finding[] }[] = [];
  const byKey = new Map<string, { key: string; items: Finding[] }>();
  for (const issue of findings) {
    const groupedReference =
      referenceRules.has(issueRuleKey(issue)) &&
      issue.field_name === "customer_reference";
    // Group only equivalent reference checks with the same status and severity.
    const key = groupedReference
      ? `customer-reference:${issue.status}:${issue.severity}:${issue.action_type}`
      : issue.id;
    let group = byKey.get(key);
    if (!group) {
      group = { key, items: [] };
      byKey.set(key, group);
      groups.push(group);
    }
    group.items.push(issue);
  }
  return groups;
}
