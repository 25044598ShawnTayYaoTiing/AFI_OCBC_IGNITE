import { RiskInvestigation } from "./RiskInvestigation";
import { CaseComparison } from "./CaseComparison";
import { ContactPhoneReview } from "./ContactPhoneReview";
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
import { groupFindings, issueCategory, issueGuidance, issueTitle } from "./DocumentFindings";

export function DocumentWorkspace() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") || "review";
  const activeTab = tab === "corrections" ? "exceptions" : tab;
  const result = useResource<Detail>(`${BASE}/submissions/${id}`, 15000);
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
  const groups = groupFindings(data?.exceptions || []);
  const chosenGroup = groups.find((group) => group.items.some((item) => item.id === chosen?.id));
  const relatedIssues = chosenGroup?.items || (chosen ? [chosen] : []);
  const selectedEvidence = relatedIssues.flatMap((item) => item.evidence);
  const locked = data?.submission.status === "OPERATIONS";
  async function run(action: string) {
    setBusy(action);
    setError("");
    setNotice("");
    try {
      await post(`${BASE}/submissions/${id}/${action}`, action === "approve" ? { revision: data!.submission.revision } : {});
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
    const sourceIssue = relatedIssues.find((item) =>
      item.evidence.some((source) => source.document_id === field.document_id && source.version_id === field.version_id),
    );
    if (sourceIssue) setFinding(sourceIssue.id);
    setPreviewRequest((value) => value + 1);
    setSelected(field.document_id);
    setVersion(field.version_id || "");
    setPreviewPage(field.page_number || 1);
    setParams({ tab: "review" });
    setReviewPane("issues");
  }
  function chooseReplacement() {
    if (doc?.extraction.method === "DIGITAL_EFORM") { setNotice("The customer must edit the digital declaration in their portal. Add supporting PDFs using Add documents."); return; }
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
    <div className="investigation-shell"><aside className="case-dossier" aria-label="Case dossier"><Link className="dossier-back" to="/submissions"><ArrowLeft size={15}/>All cases</Link><span className="edition-label">Case dossier</span><div className="dossier-avatar">{data.submission.customer_name.slice(0,1)}</div><h2>{data.submission.customer_name}</h2><p>{data.submission.customer_reference}</p><Badge value={data.submission.status}/><dl><div><dt>Service request</dt><dd>{label(data.submission.category)}</dd></div><div><dt>Intake</dt><dd>{data.submission.capture_method === "DIGITAL_EFORM" ? "Digital declaration" : "PDF documents"}</dd></div><div><dt>Current revision</dt><dd>{data.submission.revision}</dd></div><div><dt>Validation</dt><dd>{data.submission.validated_revision===data.submission.revision?"Current revision checked":"Revalidation needed"}</dd></div></dl><div className="dossier-section"><h3>Evidence library <span>{data.documents.length}</span></h3>{data.documents.length?data.documents.map(document=><button key={document.id} className="dossier-document" onClick={()=>{setSelected(document.id);setParams({tab:"review"});}}><span>PDF</span><div><strong>{label(document.document_type)}</strong><small>{document.filename}</small></div></button>):<p>No document evidence recorded.</p>}</div><div className="dossier-section"><h3>Review checkpoints</h3><button className="dossier-check" onClick={()=>setParams({tab:"exceptions"})}><span>{open.length}</span>Open findings<ArrowRight size={14}/></button><button className="dossier-check" onClick={()=>setParams({tab:"preflight"})}><span>{data.checks.filter(check=>check.passed).length}/{data.checks.length}</span>Checks passed<ArrowRight size={14}/></button><button className="dossier-check" onClick={()=>setParams({tab:"history"})}>Decision history<ArrowRight size={14}/></button></div><p className="dossier-footnote">Decisions follow backend policy. AI recommendations remain advisory.</p></aside><div className="page-stack investigation-main">
      <Link className="back-link" to="/submissions">
        <ArrowLeft size={15} />
        Submissions
      </Link>
      <PageHeader
        title={data.submission.customer_name}
        description={`${data.submission.customer_reference} / ${label(data.submission.category)} / ${data.submission.capture_method === "DIGITAL_EFORM" ? "Digital eForm" : "PDF"}`}
      >
        <Badge value={data.submission.status} />
        {!locked && data.submission.status === "RESOLUTION_MANAGEMENT" && (
          <button className="button primary" disabled={!!busy || open.length > 0 || !!data.submission.risk_hold} title={open.length ? "Resolve blocking findings and revalidate before approval" : "Approve the current validated revision"} onClick={() => void run("approve")}>
            <CheckCircle2 size={15} />Approve for Operations
          </button>
        )}
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
      {!locked && data.submission.category === "CONTACT_UPDATE" && open.some(item=>item.check_key === "SERVICE_PHONE_REVIEW") && <ContactPhoneReview id={id} revision={data.submission.revision} onUpdate={()=>void result.refresh()}/>}
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
              <dd>{["reviewer","operator"].includes(data.submission.owner_name?.toLowerCase()) ? "Resolution Management" : data.submission.owner_name}</dd>
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
      <RiskInvestigation detail={data} onUpdate={()=>void result.refresh()}/>
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
            <CaseComparison detail={data}/>
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
                description={data.submission.category === "ACCOUNT_OPENING" ? "Add the customer information form, account application and tax declaration." : "Submitted declarations and supporting PDFs appear here when available. Check the request status and findings before taking action."}
              />
            ) : (
              <div className="review-layout">
                {chosen && <div className="mobile-issue-summary" ref={issueSummary}>
                  <strong>{issueTitle(chosen)}</strong>
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
                    {!locked && doc.extraction.method !== "DIGITAL_EFORM" && (
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
                            <strong>{issueTitle(chosen)}</strong>
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
                    {!!doc.extraction.observations?.length && (
                      <div className="issue-context">
                        <strong>AI observations - not validation findings</strong>
                        <p>Review these against the PDF and applicable rules; they are not additional validation results.</p>
                        {doc.document_type === "ACCOUNT_APPLICATION" && doc.extraction.observations.some((item) => item.field === "declaration_date") && !data.checks.some((check) => check.condition_type === "DATE" && check.field_name === "declaration_date" && check.evidence.some((field) => field.document_id === doc.id)) && (
                          <p>No active mandatory-date check covers this account form. Resolution Management can propose the account declaration-date requirement in Settings; a different authorised approver must approve it before validation changes.</p>
                        )}
                        {doc.extraction.observations.map((observation, index) => (
                          <p key={`${observation.field}-${observation.page}-${index}`}>
                            {label(observation.field)} / page {observation.page}: {observation.supporting_text}
                          </p>
                        ))}
                      </div>
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
                        {data.exceptions.length > groups.length && (
                          <p className="context-note">{groups.length} issue groups / {data.exceptions.length} individual checks. Each check retains its own rule and history.</p>
                        )}
                        {data.exceptions.length ? (
                          groups.map((group) => {
                            const item = group.items[0];
                            const selected = group.items.some((member) => member.id === chosen?.id);
                            return (
                              <button
                                key={group.key}
                                className={`issue-option ${selected ? "selected" : ""}`}
                                onClick={() => {
                                  setFinding(item.id);
                                  setCompare(false);
                                }}
                              >
                                <span>
                                  <strong>{issueTitle(item)}{group.items.length > 1 ? ` (${group.items.length} checks)` : ""}</strong>
                                  <small>
                                    {item.status === "RESOLVED"
                                      ? "Resolved and verified"
                                      : item.action_type === "ATTACH_VERIFIED_DOCUMENT"
                                        ? "Automatic action requires review"
                                        : "Customer or Resolution Management action needed"}
                                  </small>
                                </span>
                                <Badge value={item.status} />
                              </button>
                            );
                          })
                        ) : (
                          <Empty
                            title="No issues recorded"
                            description="Findings appear after document validation."
                          />
                        )}
                      </div>
                    </Panel>
                    <Panel title={chosen ? issueTitle(chosen) : "Issue details"}>
                      {chosen ? (
                        <div className="panel-body">
                          <Badge value={chosen.status} />
                          <p className="content-label">{issueCategory(chosen)}</p>
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
                          {issueGuidance(chosen, data.submission.customer_reference) && (
                            <p className="context-note">{issueGuidance(chosen, data.submission.customer_reference)}</p>
                          )}
                          {selectedEvidence.length ? (
                            <>
                              <h3 className="content-label">Source evidence</h3>
                              {selectedEvidence.map((field, index) => (
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
                              {selectedEvidence.filter(
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
                          {relatedIssues.map((item) => (
                            <InvestigationExplanation
                              key={item.id}
                              investigation={data.investigations?.[0]}
                              finding={item}
                            />
                          ))}
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
                              {relatedIssues.map((item) => (
                                <div key={item.id}>
                                  <dt>Individual check</dt>
                                  <dd>{item.check_key} / {item.id}<br />{item.corrective_action}</dd>
                                </div>
                              ))}
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
                      {selectedEvidence
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
    </div></div>
  );
}
