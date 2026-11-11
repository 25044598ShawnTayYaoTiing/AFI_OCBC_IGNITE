import { ArrowRight } from "lucide-react";
import { label } from "../api";
import type { Detail, Doc, Field, Finding } from "../document-types";
import { Badge, Empty } from "../components";
import { InvestigationExplanation } from "./DocumentResolution";
import { issueCategory, issueGuidance, issueTitle } from "./DocumentFindings";

export function issueAction(issue: Finding) {
  if (issue.status !== "RESOLVED") return issue.corrective_action;
  return issue.resolution_method === "AUTOMATIC_VERIFIED_ATTACHMENT"
    ? "The authorised supporting document was attached and verified."
    : "This issue is resolved. History contains the recorded correction.";
}

export function DocumentIssueReview({
  detail,
  doc,
  issue,
  onChoose,
  onInspect,
  onDetails,
  onReplace,
  onAdd,
}: {
  detail: Detail;
  doc: Doc;
  issue?: Finding;
  onChoose: (id: string) => void;
  onInspect: (field: Field) => void;
  onDetails: () => void;
  onReplace: () => void;
  onAdd: () => void;
}) {
  const related = detail.exceptions.filter(
    (item) =>
      item.id === issue?.id ||
      !item.evidence.length ||
      item.evidence.some((field) => field.document_id === doc.id),
  );
  const locked = detail.submission.status === "OPERATIONS";
  if (!issue)
    return (
      <Empty
        title={
          detail.submission.validated_revision === detail.submission.revision
            ? "No issues on this document"
            : "Validation needed"
        }
        description={
          detail.submission.validated_revision === detail.submission.revision
            ? "Choose another PDF to review its findings."
            : "Recheck the package to confirm its current requirements."
        }
      />
    );
  return (
    <div className="focused-issue">
      <label className="issue-picker">
        Issue to review
        <select
          value={issue.id}
          onChange={(event) => onChoose(event.target.value)}
        >
          {related.map((item) => (
            <option key={item.id} value={item.id}>
              {issueTitle(item)}
              {item.status === "RESOLVED" ? " (resolved)" : ""}
            </option>
          ))}
        </select>
      </label>
      <div className="task-heading">
        <h3>{issueTitle(issue)}</h3>
        <Badge value={issue.status} />
      </div>
      <p className="content-label">{issueCategory(issue)}</p>
      <span className="content-label">
        {issue.status === "RESOLVED" ? "Recorded outcome" : "Next action"}
      </span>
      <p>{issueAction(issue)}</p>
      {issueGuidance(issue, detail.submission.customer_reference) && (
        <p className="context-note">{issueGuidance(issue, detail.submission.customer_reference)}</p>
      )}
      {issue.evidence.length ? (
        <>
          <h3 className="content-label">Source evidence</h3>
          {issue.evidence.map((field, index) => (
            <div
              className={`source-evidence ${field.document_id === doc.id ? "selected-source" : ""}`}
              key={`${field.version_id}-${field.field_name}-${index}`}
            >
              <span>
                {field.filename || label(field.field_name)} /{" "}
                {field.page_number
                  ? `page ${field.page_number}`
                  : "location unconfirmed"}
              </span>
              <strong>
                <mark>{field.field_value || "Uncertain extraction"}</mark>
              </strong>
              <p>{field.supporting_text || "Review the original PDF."}</p>
              {field.document_id && (
                <button className="text-link" onClick={() => onInspect(field)}>
                  {field.document_id === doc.id
                    ? "View this source page"
                    : "Open related PDF"}
                  <ArrowRight size={14} />
                </button>
              )}
            </div>
          ))}
        </>
      ) : (
        <p className="context-note">
          This is a package requirement. No submitted PDF supplied the required
          evidence when it was checked.
        </p>
      )}
      {!locked && issue.status === "OPEN" && (
        <button
          className="button primary issue-next-action"
          onClick={
            issue.evidence.some((field) => field.document_id === doc.id)
              ? onReplace
              : onAdd
          }
        >
          {issue.evidence.some((field) => field.document_id === doc.id)
            ? "Replace current PDF"
            : "Add required document"}
        </button>
      )}
      <button className="text-link issue-details-link" onClick={onDetails}>
        Full issue details <ArrowRight size={14} />
      </button>
      <InvestigationExplanation
        investigation={detail.investigations?.[0]}
        finding={issue}
      />
    </div>
  );
}
