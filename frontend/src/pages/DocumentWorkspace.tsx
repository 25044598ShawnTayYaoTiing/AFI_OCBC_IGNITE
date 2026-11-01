import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  RefreshCw,
  UploadCloud,
} from "lucide-react";
import { dateTime, label, post, uploadPdfs, useResource } from "../api";
import type { Detail, Field } from "../document-types";
import {
  Badge,
  Empty,
  ErrorBox,
  Loading,
  Notice,
  PageHeader,
  Panel,
} from "../components";
import { Disclosure, Tabs, TabPanel } from "../ui";
import { BASE, Checklist, message, PdfPreview } from "./DocumentShared";
import {
  AutonomyEvidence,
  CorrectionTasks,
  HandoverHistory,
  InvestigationExplanation,
  PreflightView,
} from "./DocumentResolution";

import { DocumentIssueReview, issueAction } from "./DocumentIssueReview";

export function DocumentWorkspace() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") || "review";
  const activeTab = tab === "corrections" ? "exceptions" : tab;
  const result = useResource<Detail>(`${BASE}/submissions/${id}`);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [progress, setProgress] = useState(0);
  const [selected, setSelected] = useState("");
  const [version, setVersion] = useState("");
  const [finding, setFinding] = useState("");
  const [compare, setCompare] = useState(false);
  const [previewPage, setPreviewPage] = useState(1);
  const [reviewPane, setReviewPane] = useState("issues");
  const preview = useRef<HTMLDivElement>(null);
  const issueSummary = useRef<HTMLDivElement>(null);
  const [previewRequest, setPreviewRequest] = useState(0);
  useEffect(() => {
    if (!previewRequest || !preview.current) return;
    const top = preview.current.getBoundingClientRect().top + window.scrollY;
    window.scrollTo({ top: Math.max(0, top - (issueSummary.current?.offsetHeight || 0) - 16), behavior: "instant" });
    preview.current.querySelector("select")?.focus({ preventScroll: true });
  }, [previewRequest]);
  const fileInput = useRef<HTMLInputElement>(null);
  const addInput = useRef<HTMLInputElement>(null);
  const [replacement, setReplacement] = useState<string>();
  useEffect(() => {
    setSelected("");
    setVersion("");
    setFinding("");
    setCompare(false);
    setError("");
    setNotice("");
    setPreviewPage(1);
    setReviewPane("issues");
  }, [id]);
  const data = result.data;
  const open = data?.exceptions.filter((item) => item.status === "OPEN") || [];
  const relevant = selected
    ? [
        ...(data?.exceptions.filter((item) =>
          item.evidence.some((field) => field.document_id === selected),
        ) || []),
        ...(data?.exceptions.filter((item) => !item.evidence.length) || []),
      ]
    : data?.exceptions || [];
  const chosen =
    data?.exceptions.find((item) => item.id === finding) ||
    relevant.find((item) => item.status === "OPEN") ||
    relevant[0];
  const doc =
    data?.documents.find((item) => item.id === selected) ||
    data?.documents.find((item) =>
      chosen?.evidence.some((field) => field.document_id === item.id),
    ) ||
    data?.documents[0];
  const locked = data?.submission.status === "OPERATIONS";
  async function run(action: string) {
    setBusy(action);
    setError("");
    setNotice("");
    try {
      await post(`${BASE}/submissions/${id}/${action}`);
      await result.refresh();
      setNotice(
        action === "preflight"
          ? "Readiness snapshot saved."
          : "Validation complete. Review the updated result.",
      );
      if (action === "preflight") setParams({ tab: "preflight" });
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy("");
    }
  }
  async function upload(files: File[], replace?: string) {
    if (!files.length) return;
    setError("");
    setNotice("");
    if (
      files.length > 10 ||
      files.some(
        (file) =>
          !file.name.toLowerCase().endsWith(".pdf") ||
          file.size > 10 * 1024 * 1024 ||
          file.size === 0,
      )
    ) {
      setError("Choose up to 10 nonempty PDFs, no larger than 10 MB each.");
      return;
    }
    setBusy("upload");
    setProgress(0);
    try {
      await uploadPdfs(
        `${BASE}/submissions/${id}/upload${replace ? `?replace=${encodeURIComponent(replace)}` : ""}`,
        files,
        setProgress,
      );
      setVersion("");
      await result.refresh();
      setNotice(
        "Documents received. Review the validation and resolution results below.",
      );
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy("");
      setReplacement(undefined);
    }
  }
  function inspect(field: Field) {
    setPreviewRequest((value) => value + 1);
    setSelected(field.document_id);
    setVersion(field.version_id || "");
    setPreviewPage(field.page_number || 1);
    setParams({ tab: "review" });
    setReviewPane("issues");
  }
  function chooseReplacement() {
    if (doc) {
      setReplacement(doc.id);
      fileInput.current?.click();
    }
  }
  if (result.loading) return <Loading />;
  if (result.error || !data)
    return (
      <ErrorBox
        message={result.error || "Submission unavailable"}
        retry={() => void result.refresh()}
      />
    );
  return (
    <div className="page-stack">
      <Link className="back-link" to="/submissions">
        <ArrowLeft size={15} />
        Submissions
      </Link>
      <PageHeader
        title={data.submission.customer_name}
        description={`${data.submission.customer_reference} / Account opening`}
      >
        <Badge value={data.submission.status} />
        {!locked && (
          <button
            className="button secondary"
            disabled={!!busy || !data.documents.length}
            onClick={() => void run("validate")}
          >
            <RefreshCw size={15} />
            {busy === "validate" ? "Checking..." : "Recheck package"}
          </button>
        )}
      </PageHeader>
      {error && <ErrorBox message={error} />}{" "}
      {notice && <Notice success>{notice}</Notice>}
      {busy && (
        <div className="operation-progress" role="status">
          {busy === "upload" ? (
            <>
              <progress value={progress} max={100} />
              <span>
                {progress < 100
                  ? `Uploading documents (${progress}%)`
                  : "Reading documents and checking the package..."}
              </span>
            </>
          ) : (
            <span>Checking the package...</span>
          )}
        </div>
      )}
      <div className={`case-summary ${locked ? "case-complete" : ""}`}>
        <CheckCircle2 size={20} />
        <div>
          <strong>
            {locked
              ? "Package received by Operations"
              : open.length
                ? `${open.length} ${open.length === 1 ? "issue needs" : "issues need"} attention`
                : "Document review"}
          </strong>
          <p>
            {locked
              ? `Sent ${dateTime(data.submission.routed_at)} SGT. Documents and resolution history are available below.`
              : open.length
                ? "Review the issues, then replace any documents that need correction. Eligible administrative issues are handled automatically."
                : "Add the related PDFs. Validation and eligible automatic actions start after upload."}
          </p>
        </div>
        <Disclosure title="Case details">
          <dl className="metadata">
            <div>
              <dt>Submission</dt>
              <dd>{data.submission.id}</dd>
            </div>
            <div>
              <dt>Owner</dt>
              <dd>{data.submission.owner_name}</dd>
            </div>
            <div>
              <dt>Package revision</dt>
              <dd>{data.submission.revision}</dd>
            </div>
          </dl>
        </Disclosure>
      </div>
      {data.submission.validated_revision !== data.submission.revision &&
        data.exceptions.length > 0 && (
          <Notice>
            The documents or rules changed. Earlier findings remain available
            until the current package is rechecked.
          </Notice>
        )}
      <Tabs
        id="submission-workspace"
        className="section-nav workspace-tabs"
        label="Submission workspace"
        value={activeTab}
        items={[
          { value: "review", label: "Document review" },
          {
            value: "exceptions",
            label: `Issues & corrections${open.length ? ` (${open.length})` : ""}`,
          },
          { value: "automation", label: "Automation" },
          { value: "preflight", label: "Preflight" },
          { value: "history", label: "History" },
        ]}
        onChange={(key) => {
          setParams({ tab: key });
          setCompare(false);
        }}
      />
      <input
        ref={fileInput}
        hidden
        type="file"
        aria-label="Upload replacement PDF"
        accept="application/pdf,.pdf"
        disabled={!!busy || locked}
        onChange={(event) => {
          void upload(Array.from(event.target.files || []), replacement);
          event.target.value = "";
        }}
      />
      <TabPanel
        id="submission-workspace"
        value={activeTab}
        className="page-stack workspace-panel"
      >
        {tab === "review" && (
          <>
            {!locked && (
              <div
                className="upload-area"
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  if (!busy) void upload(Array.from(event.dataTransfer.files));
                }}
              >
                <UploadCloud size={22} />
                <div>
                  <strong>
                    {data.documents.length
                      ? "Add documents"
                      : "Upload the customer forms"}
                  </strong>
                  <p>Drop PDFs here, or choose files. Up to 10 MB per PDF.</p>
                </div>
                <label
                  className={`button ${data.documents.length ? "secondary" : "primary"}`}
                >
                  Choose files
                  <input
                    ref={addInput}
                    className="sr-only"
                    type="file"
                    multiple
                    accept="application/pdf,.pdf"
                    disabled={!!busy}
                    onChange={(event) => {
                      void upload(Array.from(event.target.files || []));
                      event.target.value = "";
                    }}
                  />
                </label>
              </div>
            )}
            {!doc ? (
              <Empty
                title="Ready for the document package"
                description="Add the customer information form, account application and tax declaration."
              />
            ) : (
              <div className="review-layout">
                {chosen && <div className="mobile-issue-summary" ref={issueSummary}>
                  <strong>{chosen.name}</strong>
                  <p>{issueAction(chosen)}</p>
                </div>}
                <div className="preview-column" ref={preview}>
                <Panel className="preview-panel">
                  <div className="document-toolbar">
                    <label>
                      <span className="sr-only">Document to inspect</span>
                      <select
                        value={doc.id}
                        onChange={(event) => {
                          setSelected(event.target.value);
                          setFinding("");
                          setVersion("");
                          setPreviewPage(1);
                        }}
                      >
                        {data.documents.map((item) => (
                          <option key={item.id} value={item.id}>
                            {label(item.document_type)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <select
                      aria-label="Document version"
                      value={version || doc.version_id}
                      onChange={(event) => setVersion(event.target.value)}
                    >
                      {doc.versions.map((item) => (
                        <option key={item.id} value={item.id}>
                          Version {item.version}
                          {item.id === doc.version_id ? " (current)" : ""}
                        </option>
                      ))}
                    </select>
                    {!locked && (
                      <button
                        className="button secondary"
                        disabled={!!busy}
                        onClick={chooseReplacement}
                      >
                        Replace PDF
                      </button>
                    )}
                  </div>
                  <PdfPreview
                    caseId={id}
                    versionId={version || doc.version_id}
                    page={previewPage}
                  />
                </Panel>
                </div>
                <Panel title="Review findings" className="review-sidebar">
                  <Tabs
                    id="document-information"
                    label="Document information"
                    value={reviewPane}
                    onChange={setReviewPane}
                    items={[
                      { value: "issues", label: "Issues & next steps" },
                      { value: "fields", label: "Extracted fields" },
                    ]}
                  />
                  <TabPanel
                    id="document-information"
                    value={reviewPane}
                    className="panel-body"
                  >
                    {reviewPane === "issues" ? (
                      <DocumentIssueReview
                        detail={data}
                        doc={doc}
                        issue={chosen}
                        onChoose={(key) => {
                          setFinding(key);
                          const issue = data.exceptions.find(
                            (item) => item.id === key,
                          );
                          const source =
                            issue?.evidence.find(
                              (field) => field.document_id === doc.id,
                            ) || issue?.evidence[0];
                          if (source) {
                            setSelected(source.document_id);
                            setVersion(source.version_id || "");
                            setPreviewPage(source.page_number || 1);
                          }
                        }}
                        onInspect={inspect}
                        onDetails={() => setParams({ tab: "exceptions" })}
                        onReplace={chooseReplacement}
                        onAdd={() => addInput.current?.click()}
                      />
                    ) : (
                      <>
                        {chosen && (
                          <div className="issue-context">
                            <strong>{chosen.name}</strong>
                            <p>{issueAction(chosen)}</p>
                          </div>
                        )}
                        <div className="provenance-line">
                          <Badge value={doc.extraction.method} />
                          <span>Current version {doc.current_version}</span>
                        </div>
                        {version && version !== doc.version_id ? (
                          <Notice>
                            You're viewing an earlier PDF. Current extracted
                            values are hidden to avoid mixing document versions.
                          </Notice>
                        ) : (
                          <dl className="field-list">
                            {doc.fields.map((field) => (
                              <div key={field.field_name}>
                                <dt>{label(field.field_name)}</dt>
                                <dd>{field.field_value}</dd>
                              </div>
                            ))}
                          </dl>
                        )}
                        {(!version || version === doc.version_id) && (
                          <Disclosure title="Extraction evidence">
                            <p>{doc.extraction.notes}</p>
                            {doc.fields.map((field) => (
                              <div
                                className="evidence-line"
                                key={field.field_name}
                              >
                                <strong>
                                  {label(field.field_name)} / page{" "}
                                  {field.page_number ?? "unconfirmed"}
                                </strong>
                                <p>{field.supporting_text}</p>
                              </div>
                            ))}
                          </Disclosure>
                        )}
                      </>
                    )}
                  </TabPanel>
                </Panel>
              </div>
            )}
          </>
        )}
        {(tab === "exceptions" || tab === "corrections") && (
          <>
            <Tabs
              id="issue-views"
              label="Issue views"
              value={tab}
              onChange={(key) => setParams({ tab: key })}
              items={[
                { value: "exceptions", label: "Issues" },
                { value: "corrections", label: "Correction tasks" },
              ]}
            />
            <TabPanel id="issue-views" value={tab} className="page-stack">
              {tab === "corrections" ? (
                <CorrectionTasks
                  detail={data}
                  onUpload={() => setParams({ tab: "review" })}
                />
              ) : (
                <>
                  <div className="issues-layout">
                    <Panel title="Validation issues">
                      <div className="issue-list">
                        {data.exceptions.length ? (
                          data.exceptions.map((item) => (
                            <button
                              key={item.id}
                              className={`issue-option ${chosen?.id === item.id ? "selected" : ""}`}
                              onClick={() => {
                                setFinding(item.id);
                                setCompare(false);
                              }}
                            >
                              <span>
                                <strong>{item.name}</strong>
                                <small>
                                  {item.status === "RESOLVED"
                                    ? "Resolved and verified"
                                    : item.action_type ===
                                        "ATTACH_VERIFIED_DOCUMENT"
                                      ? "Automatic action requires review"
                                      : "Customer or reviewer action needed"}
                                </small>
                              </span>
                              <Badge value={item.status} />
                            </button>
                          ))
                        ) : (
                          <Empty
                            title="No issues recorded"
                            description="Findings appear after document validation."
                          />
                        )}
                      </div>
                    </Panel>
                    <Panel title={chosen?.name || "Issue details"}>
                      {chosen ? (
                        <div className="panel-body">
                          <Badge value={chosen.status} />
                          <h3 className="content-label">
                            {chosen.status === "RESOLVED"
                              ? "Completed action"
                              : "What needs to happen"}
                          </h3>
                          <p>
                            {chosen.status === "RESOLVED" &&
                            chosen.resolution_method ===
                              "AUTOMATIC_VERIFIED_ATTACHMENT"
                              ? "The authorised supporting document was attached and verified."
                              : chosen.corrective_action}
                          </p>
                          {chosen.evidence.length ? (
                            <>
                              <h3 className="content-label">Source evidence</h3>
                              {chosen.evidence.map((field, index) => (
                                <div className="source-evidence" key={index}>
                                  <span>
                                    {field.filename || "Source document"} /{" "}
                                    {field.page_number
                                      ? `page ${field.page_number}`
                                      : "location unconfirmed"}
                                  </span>
                                  <strong>
                                    <mark>
                                      {field.field_value ||
                                        "Uncertain extraction"}
                                    </mark>
                                  </strong>
                                  <p>
                                    {field.supporting_text ||
                                      "Review the source document."}
                                  </p>
                                  {field.document_id && (
                                    <button
                                      className="text-link"
                                      onClick={() => inspect(field)}
                                    >
                                      Open original <ArrowRight size={14} />
                                    </button>
                                  )}
                                </div>
                              ))}
                              {chosen.evidence.filter(
                                (field) => field.version_id,
                              ).length > 1 && (
                                <button
                                  className="button secondary"
                                  onClick={() => setCompare(!compare)}
                                >
                                  {compare
                                    ? "Close comparison"
                                    : "Compare original PDFs"}
                                </button>
                              )}
                            </>
                          ) : (
                            <p className="muted">
                              No submitted document satisfied this package
                              requirement when the finding was recorded.
                            </p>
                          )}
                          <InvestigationExplanation
                            investigation={data.investigations?.[0]}
                            finding={chosen}
                          />
                          <Disclosure title="Rule details">
                            <dl className="metadata">
                              <div>
                                <dt>Applied rule</dt>
                                <dd>{chosen.name}</dd>
                              </div>
                              <div>
                                <dt>Severity</dt>
                                <dd>{label(chosen.severity)}</dd>
                              </div>
                              <div>
                                <dt>Resolution method</dt>
                                <dd>{label(chosen.resolution_method)}</dd>
                              </div>
                              <div>
                                <dt>Issue reference</dt>
                                <dd>{chosen.id}</dd>
                              </div>
                            </dl>
                          </Disclosure>
                          {chosen.status === "OPEN" && !locked && (
                            <button
                              className="button secondary"
                              onClick={() => setParams({ tab: "review" })}
                            >
                              Review or replace documents{" "}
                              <ArrowRight size={14} />
                            </button>
                          )}
                        </div>
                      ) : (
                        <Empty title="No issue selected" />
                      )}
                    </Panel>
                  </div>
                  {compare && chosen && (
                    <div className="source-comparison">
                      {chosen.evidence
                        .filter((field) => field.version_id)
                        .slice(0, 3)
                        .map((field) => (
                          <Panel
                            key={field.version_id}
                            title={field.filename}
                            subtitle={`${field.field_value} / page ${field.page_number ?? "unconfirmed"}`}
                          >
                            <PdfPreview
                              caseId={id}
                              versionId={field.version_id}
                              page={field.page_number || 1}
                            />
                          </Panel>
                        ))}
                    </div>
                  )}
                  <Disclosure
                    title={`Validation checklist (${data.checks.filter((check) => check.passed).length}/${data.checks.length} passed)`}
                  >
                    <Checklist checks={data.checks} />
                  </Disclosure>
                </>
              )}
            </TabPanel>
          </>
        )}
        {tab === "automation" && <AutonomyEvidence detail={data} />}
        {tab === "preflight" && (
          <PreflightView
            detail={data}
            busy={!!busy}
            onRun={() => void run("preflight")}
          />
        )}
        {tab === "history" && <HandoverHistory detail={data} />}
      </TabPanel>
    </div>
  );
}
