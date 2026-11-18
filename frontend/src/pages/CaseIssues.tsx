import { ArrowRight, FileSearch } from "lucide-react";
import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { groupFindings, issueTitle } from "./DocumentFindings";

export function CaseIssues({ detail, onInspect, selected, onSelect }: {
  detail: Detail;
  onInspect: (field: Field) => void;
  selected: string;
  onSelect: (id: string) => void;
}) {
  const groups = groupFindings(detail.exceptions.filter((issue) => issue.status === "OPEN"));
  const group = groups.find((item) => item.items.some((issue) => issue.id === selected)) || groups[0];
  if (!group) return null;
  const issue = group.items[0];
  const evidence = group.items.flatMap((item) => item.evidence).filter((field, index, all) =>
    all.findIndex((other) => other.version_id === field.version_id && other.field_name === field.field_name &&
      other.field_value === field.field_value) === index);
  const investigation = detail.investigations.find((item) => item.revision === detail.submission.revision);
  const explanation = investigation?.method === "GEMINI"
    ? investigation.recommendations.find((item) => item.exception_id === issue.id)?.explanation
    : undefined;
  return (
    <section className="case-issues" aria-label="Open issues">
      <div className="workspace-section-heading">
        <div>
          <h2>Open issues ({groups.length})</h2>
          <p>Review the requirement, cited evidence and next action.</p>
        </div>
      </div>
      {groups.length > 1 && (
        <div className="demo-issue-options" aria-label="Choose an issue">
          {groups.map((item) => (
            <button
              key={item.key}
              className={`button secondary ${item === group ? "active-issue-pill" : ""}`}
              aria-pressed={item === group}
              onClick={() => onSelect(item.items[0].id)}
            >
              {issueTitle(item.items[0])}
            </button>
          ))}
        </div>
      )}
      <article className="issue-detail demo-issue-card">
        <div className="issue-detail-heading">
          <h3>{issueTitle(issue)}</h3>
          <span className="issue-state">Requires action</span>
        </div>
        <div className="task-action-callout">
          <span className="task-action-label">Required action</span>
          <p>{issue.corrective_action || issue.description}</p>
        </div>
        {!!evidence.length && (
          <section
            className="issue-sources demo-evidence-grid"
            aria-label="Supporting evidence"
          >
            {evidence.map((field, index) => {
              const document = detail.documents.find((doc) =>
                doc.versions.some((version) => version.id === field.version_id),
              );
              return (
                <div className="citation-card" key={index}>
                  <div className="citation-card-top">
                    <span className="citation-field">
                      {label(field.field_name)}
                    </span>
                    <small>
                      {field.method === "SYNTHETIC_CUSTOMER_PROFILE"
                        ? "Stored bank record"
                        : field.filename || "Recorded evidence"}
                    </small>
                  </div>
                  <strong className="citation-value">
                    {field.field_value || "Unconfirmed"}
                  </strong>
                  {document && (
                    <button
                      className="text-link"
                      onClick={() => onInspect(field)}
                    >
                      <FileSearch size={14} aria-hidden="true" />
                      View source
                      {field.page_number
                        ? " · page " + field.page_number
                        : ""}{" "}
                      <ArrowRight size={14} />
                    </button>
                  )}
                </div>
              );
            })}
          </section>
        )}
        {explanation && (
          <div className="ai-advisory-box">
            <span className="task-action-label">
              AI investigation summary (advisory)
            </span>
            <p>{explanation}</p>
          </div>
        )}
      </article>
    </section>
  );
}
