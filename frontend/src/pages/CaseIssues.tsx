import { useState } from "react";
import {
  ArrowRight,
  FileSearch,
  CheckCircle2,
  CircleAlert,
} from "lucide-react";
import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { Disclosure } from "../ui";
import { Empty } from "../components";
import { groupFindings, issueTitle } from "./DocumentFindings";
import { InvestigationExplanation } from "./DocumentResolution";
import { reviewState } from "./review-state";

export function CaseIssues({
  detail,
  onInspect,
  onDocuments,
  selected,
  onSelect,
}: {
  detail: Detail;
  onInspect: (field: Field) => void;
  onDocuments: () => void;
  selected: string;
  onSelect: (id: string) => void;
}) {
  const [showResolved, setShowResolved] = useState(false);
  const state = reviewState(detail);
  const groups = groupFindings(
    detail.exceptions.filter(
      (issue) => showResolved || issue.status === "OPEN",
    ),
  );
  const group =
    groups.find((group) =>
      group.items.some((issue) => issue.id === selected),
    ) || groups[0];
  const issue = group?.items[0];
  const task = detail.tasks.find(
    (task) =>
      group?.items.some((issue) => issue.id === task.exception_id) &&
      task.status !== "RESOLVED",
  );
  const evidence = (
    group?.items.flatMap((issue) => issue.evidence) || []
  ).filter(
    (field, index, fields) =>
      fields.findIndex(
        (other) =>
          other.version_id === field.version_id &&
          other.field_name === field.field_name &&
          other.field_value === field.field_value,
      ) === index,
  );
  const profile = evidence.filter(
    (field) => field.method === "SYNTHETIC_CUSTOMER_PROFILE",
  );
  const submitted = evidence.filter(
    (field) => field.method !== "SYNTHETIC_CUSTOMER_PROFILE",
  );
  const owner =
    issue?.status === "CANCELLED"
      ? "Request cancelled"
      : issue?.status === "RESOLVED"
        ? "Completed"
        : task
          ? label(task.assigned_role)
          : "Case owner to coordinate";
  return (
    <section className="case-issues" aria-label="Issues requiring attention">
      <div className="workspace-section-heading">
        <div>
          <h2>Issues requiring attention</h2>
          <p>
            {state.current
              ? "Review the problem, source evidence and required action together."
              : "Earlier findings are shown for context. Recheck this revision before deciding."}
          </p>
        </div>
        <label className="resolved-toggle">
          <input
            type="checkbox"
            checked={showResolved}
            onChange={(event) => setShowResolved(event.target.checked)}
          />
          Include closed
        </label>
      </div>
      {!issue ? (
        <div className="review-empty">
          <CheckCircle2 size={27} />
          <Empty
            title={
              state.current
                ? "No open issues recorded"
                : "Current validation needed"
            }
            description={
              state.current
                ? "Review documents and the decision panel for the next step."
                : "No findings does not confirm readiness. Run Recheck package."
            }
          />
          <button className="button secondary" onClick={onDocuments}>
            Review documents
          </button>
        </div>
      ) : (
        <div className="unified-issues-layout">
          <nav className="issue-selection" aria-label="Choose an issue">
            {groups.map((item) => {
              const first = item.items[0];
              const assigned = detail.tasks.find(
                (task) =>
                  task.exception_id === first.id && task.status !== "RESOLVED",
              );
              return (
                <button
                  key={item.key}
                  className={group?.key === item.key ? "selected" : ""}
                  aria-pressed={group?.key === item.key}
                  onClick={() => onSelect(first.id)}
                >
                  {first.status === "RESOLVED" ? (
                    <CheckCircle2 size={18} />
                  ) : (
                    <CircleAlert size={18} />
                  )}
                  <span>
                    <strong>{issueTitle(first)}</strong>
                    <small>
                      {first.status === "CANCELLED"
                        ? "Cancelled with request"
                        : first.status === "RESOLVED"
                          ? "Resolved and verified"
                          : assigned
                            ? label(assigned.assigned_role)
                            : "Case owner"}
                      {item.items.length > 1
                        ? ` · ${item.items.length} checks`
                        : ""}
                    </small>
                  </span>
                </button>
              );
            })}
          </nav>
          <article className="issue-detail" aria-label="Selected issue">
            <div className="issue-detail-heading">
              <span
                className={`issue-state ${issue.status === "RESOLVED" ? "resolved" : ""}`}
              >
                {issue.status === "CANCELLED"
                  ? "Cancelled with request"
                  : issue.status === "RESOLVED"
                    ? "Resolved and verified"
                    : "Unresolved"}
              </span>
              <span className="issue-owner">Next: {owner}</span>
            </div>
            <h3>{issueTitle(issue)}</h3>
            <p>{issue.description || issue.name}</p>
            <div className="issue-requirement">
              <h4>Required next action</h4>
              <p>
                {issue.status === "CANCELLED"
                  ? "No further action: the customer cancelled this request. Original findings are retained for history."
                  : issue.status === "RESOLVED"
                    ? "The recorded issue has been resolved. Review its history for the completed action."
                    : issue.corrective_action}
              </p>
            </div>
            <div className="issue-value-comparison">
              <section>
                <h4>Expected information</h4>
                {profile.length ? (
                  profile.map((field, index) => (
                    <p key={index}>
                      <span>{label(field.field_name)}</span>
                      <strong>{field.field_value || "Not recorded"}</strong>
                    </p>
                  ))
                ) : (
                  <p>
                    {issue.condition_type === "ATTACHMENT"
                      ? `Required document: ${label(issue.document_type || "Document type not specified")}.`
                      : "Use the requirement above. No separate expected field value was returned."}
                  </p>
                )}
              </section>
              <section>
                <h4>Submitted / extracted</h4>
                {submitted.length ? (
                  submitted.map((field, index) => (
                    <p key={index}>
                      <span>{label(field.field_name)}</span>
                      <strong>{field.field_value || "Not confirmed"}</strong>
                    </p>
                  ))
                ) : (
                  <p>
                    No source value was recorded for this finding. The absence
                    of a citation alone does not prove a document is missing.
                  </p>
                )}
              </section>
            </div>
            {!!submitted.length && (
              <section className="issue-sources">
                <h4>Source evidence</h4>
                {submitted.map((field, index) => (
                  <div key={index}>
                    <FileSearch size={17} />
                    <div>
                      <strong>
                        {field.filename || "Recorded evidence"}
                        {field.page_number
                          ? ` · Page ${field.page_number}`
                          : " · Page unconfirmed"}
                      </strong>
                      <p>
                        {field.supporting_text || "No source quote returned."}
                      </p>
                      {detail.documents.some(
                        (document) =>
                          document.id === field.document_id &&
                          document.versions.some(
                            (version) => version.id === field.version_id,
                          ),
                      ) && (
                        <button
                          className="text-link"
                          onClick={() => onInspect(field)}
                        >
                          Inspect original evidence
                          <ArrowRight size={14} />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </section>
            )}
            {issue.status === "OPEN" && !state.routed && (
              <div className="issue-action-row">
                <button className="button secondary" onClick={onDocuments}>
                  Review or add documents
                  <ArrowRight size={15} />
                </button>
                <p>
                  {task
                    ? `Task: ${label(task.status)}. ${owner} owns the next step.`
                    : "No correction task assignment was returned. The case owner should coordinate."}
                  {issue.action_type === "ATTACH_VERIFIED_DOCUMENT"
                    ? " Automated attachment remains subject to backend authorisation and verification."
                    : ""}
                </p>
              </div>
            )}
            <Disclosure title="AI investigation analysis (advisory)">
              {group?.items.map((item) => (
                <InvestigationExplanation
                  key={item.id}
                  investigation={detail.investigations?.[0]}
                  finding={item}
                  detail={detail}
                  onInspect={onInspect}
                  inline
                />
              ))}
            </Disclosure>
            <Disclosure title="Technical rule details">
              <p className="muted small">
                {issue.rule_description ||
                  "Deterministic backend intake policy evaluation."}
              </p>
              {group?.items.map((item) => (
                <dl className="metadata" key={item.id}>
                  <div>
                    <dt>Check key</dt>
                    <dd className="mono">{item.check_key}</dd>
                  </div>
                  <div>
                    <dt>Rule version</dt>
                    <dd>Version {item.rule_version ?? 1}</dd>
                  </div>
                  <div>
                    <dt>Severity</dt>
                    <dd>{label(item.severity)}</dd>
                  </div>
                  <div>
                    <dt>Resolution method</dt>
                    <dd>
                      {item.resolution_method
                        ? label(item.resolution_method)
                        : "Pending"}
                    </dd>
                  </div>
                </dl>
              ))}
            </Disclosure>
          </article>
        </div>
      )}
    </section>
  );
}
