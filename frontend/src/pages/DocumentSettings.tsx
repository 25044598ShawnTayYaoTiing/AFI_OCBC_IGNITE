import { useState } from "react";
import { RefreshCw } from "lucide-react";
import { dateTime, label, post, useResource } from "../api";
import type { AiStatus, Rule } from "../document-types";
import { Badge, Empty, ErrorBox, Loading, Notice, Panel } from "../components";
import { isReviewer, useAuth } from "../auth";
import { Disclosure } from "../ui";
import { BASE, message } from "./DocumentShared";

export function DocumentAiStatus() {
  const result = useResource<AiStatus>(`${BASE}/ai-status`, 30000);
  const state = result.data;
  const titles: Record<string, string> = {
    SUCCEEDING: "Live requests are succeeding",
    DEGRADED: "A recent request failed",
    NOT_TESTED: "Configured, awaiting a live request",
    NOT_CONFIGURED: "Gemini is not configured",
    SERVICE_UNAVAILABLE: "AI service unavailable",
  };
  return (
    <Panel
      title="Gemini connection"
      action={
        <button
          className="button secondary"
          onClick={() => void result.refresh()}
        >
          <RefreshCw size={15} />
          Refresh status
        </button>
      }
    >
      <div className="panel-body">
        {result.error ? (
          <ErrorBox message={result.error} />
        ) : !state ? (
          <Loading />
        ) : (
          <>
            <div className="integration-status">
              <span
                className={`connection-dot ${state.liveStatus === "SUCCEEDING" ? "connected" : ""}`}
              />
              <div>
                <h3>{titles[state.liveStatus] || label(state.liveStatus)}</h3>
                <p>
                  {state.configured === null
                    ? "Configuration status is unavailable."
                    : state.configured
                      ? "Gemini is configured for document analysis."
                      : "Set the private server configuration to enable live Gemini."}
                </p>
              </div>
            </div>
            <dl className="summary-list">
              <div>
                <dt>Active model</dt>
                <dd>{state.model || "Not available"}</dd>
              </div>
              <div>
                <dt>Last successful request</dt>
                <dd>{dateTime(state.lastLiveSuccessAt)} SGT</dd>
              </div>
            </dl>
            <Disclosure title="Advanced details">
              <dl className="summary-list">
                <div>
                  <dt>Successful live requests</dt>
                  <dd>{state.liveRequestsSucceeded ?? 0}</dd>
                </div>
                <div>
                  <dt>Failed live requests</dt>
                  <dd>{state.liveRequestsFailed ?? 0}</dd>
                </div>
                <div>
                  <dt>Last failure</dt>
                  <dd>
                    {state.lastFailureCode
                      ? label(state.lastFailureCode)
                      : "None recorded"}
                  </dd>
                </div>
              </dl>
              <p>
                Counters cover this AI service process. Per-document provenance
                and investigations are retained with the case. Refreshing status
                does not make a model request.
              </p>
            </Disclosure>
          </>
        )}
      </div>
    </Panel>
  );
}

export function ValidationRules() {
  const { session } = useAuth();
  const result = useResource<Rule[]>(`${BASE}/rules`);
  const [selected, setSelected] = useState("");
  const [action, setAction] = useState("");
  const [severity, setSeverity] = useState("BLOCKING");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("current");
  const items = (result.data || []).filter(
    (rule) =>
      (status === "all" ||
        (status === "current" && rule.status !== "RETIRED") ||
        rule.status === status) &&
      `${rule.name} ${rule.document_type} ${rule.field_name}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  async function run(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await post(path, body);
      await result.refresh();
      setSelected("");
      setNotice(
        body
          ? "Draft saved. A different authorised approver must approve it."
          : "Rule version approved. Open packages require revalidation.",
      );
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page-stack">
      {error && <ErrorBox message={error} />}{" "}
      {notice && <Notice success>{notice}</Notice>}
      <Panel
        title="Validation rules"
        subtitle="Approved intake requirements and revisions awaiting review."
        action={isReviewer(session) && result.data && !result.error && !result.data.some((rule) => rule.rule_key === "ACCOUNT_DATE" && rule.status !== "RETIRED") ? (
          <button className="button secondary" disabled={busy} onClick={() => {
            setSelected("ACCOUNT_DATE_PROPOSAL");
            setAction("Request a corrected account application with a valid customer-supplied declaration date.");
            setSeverity("BLOCKING");
            setError("");
          }}>Propose account date requirement</button>
        ) : undefined}
      >
        <div className="table-toolbar">
          <input
            type="search"
            aria-label="Search validation rules"
            placeholder="Search rule or form"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <select
            aria-label="Rule status"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            <option value="current">Current & awaiting review</option>
            <option value="ACTIVE">Active</option>
            <option value="DRAFT">Draft</option>
            <option value="RETIRED">Retired</option>
            <option value="all">All versions</option>
          </select>
          <span className="result-count">
            {result.error
              ? "Unavailable"
              : result.loading
                ? "Loading..."
                : `${items.length} rules`}
          </span>
        </div>
        {result.error ? (
          <ErrorBox message={result.error} />
        ) : result.loading ? (
          <Loading />
        ) : (
          <div className="table-scroll">
            <table className="rules-table">
              <thead>
                <tr>
                  <th>Requirement</th>
                  <th>Form</th>
                  <th>Version</th>
                  <th>Status</th>
                  <th>Review</th>
                </tr>
              </thead>
              <tbody>
                {items.map((rule) => (
                  <tr key={rule.id}>
                    <td>
                      <strong>{rule.name}</strong>
                      <small className="secondary-line">
                        {rule.field_name
                          ? label(rule.field_name)
                          : "Package requirement"}
                      </small>
                    </td>
                    <td>
                      {rule.document_type === "PACKAGE"
                        ? "Across forms"
                        : label(rule.document_type)}
                    </td>
                    <td>
                      v{rule.version}
                      <small className="secondary-line">
                        {rule.severity === "BLOCKING"
                          ? "Blocks handover"
                          : "Warning"}
                      </small>
                    </td>
                    <td>
                      <Badge value={rule.status} />
                    </td>
                    <td>
                      {isReviewer(session) && rule.status === "ACTIVE" ? (
                        <button
                          className="text-link"
                          onClick={() => {
                            setSelected(rule.id);
                            setAction(rule.corrective_action);
                            setSeverity(rule.severity);
                            setError("");
                          }}
                        >
                          Propose revision
                        </button>
                      ) : isReviewer(session) &&
                        rule.status === "DRAFT" &&
                        rule.created_by !== session.username ? (
                        <button
                          className="button secondary"
                          disabled={busy}
                          onClick={() =>
                            void run(`${BASE}/rules/${rule.id}/approve`)
                          }
                        >
                          Approve
                        </button>
                      ) : (
                        <span className="muted">
                          {rule.status === "DRAFT"
                            ? "Awaiting independent review"
                            : rule.approved_by
                              ? "Approved"
                              : "View only"}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!items.length && (
              <Empty
                title="No matching rules"
                description="Try another search or status."
              />
            )}
          </div>
        )}
      </Panel>
      {selected && (
        <Panel title={selected === "ACCOUNT_DATE_PROPOSAL" ? "Propose account declaration-date requirement" : "Propose a rule revision"}>
          <form
            className="form-layout"
            onSubmit={(event) => {
              event.preventDefault();
              void run(selected === "ACCOUNT_DATE_PROPOSAL" ? `${BASE}/rules/account-declaration-date/draft` : `${BASE}/rules/draft`, {
                sourceId: selected === "ACCOUNT_DATE_PROPOSAL" ? null : selected,
                correctiveAction: action,
                severity,
              });
            }}
          >
            <label>
              Correction guidance
              <textarea
                value={action}
                onChange={(event) => setAction(event.target.value)}
                maxLength={500}
                required
              />
            </label>
            <label>
              Impact
              <select
                value={severity}
                disabled={selected === "ACCOUNT_DATE_PROPOSAL"}
                onChange={(event) => setSeverity(event.target.value)}
              >
                <option value="BLOCKING">Blocks Operations handover</option>
                <option value="WARNING">Non-blocking warning</option>
              </select>
            </label>
            <p className="muted">
              A different authorised reviewer must approve this draft before it affects validation.
              {selected === "ACCOUNT_DATE_PROPOSAL" && " This proposes a blocking date check for the account application only; it does not activate the requirement."}
            </p>
            <div className="form-actions">
              <button
                type="button"
                className="button secondary"
                onClick={() => setSelected("")}
              >
                Cancel
              </button>
              <button className="button primary" disabled={busy}>
                {busy ? "Saving..." : "Save draft"}
              </button>
            </div>
          </form>
        </Panel>
      )}
      <Disclosure title="Policy safeguards">
        <p>
          Resolution Management can propose supported revisions and a new account declaration-date requirement as inactive drafts.
          Another reviewer must approve activation. Gemini
          cannot create or activate policies. Prior rule versions and approvals
          are retained.
        </p>
      </Disclosure>
    </div>
  );
}
