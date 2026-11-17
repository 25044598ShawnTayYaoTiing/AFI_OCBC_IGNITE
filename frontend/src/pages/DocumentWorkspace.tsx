import { RiskInvestigation } from "./RiskInvestigation";
import { CaseComparison } from "./CaseComparison";
import { ContactPhoneReview } from "./ContactPhoneReview";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ArrowRight, UploadCloud } from "lucide-react";
import { label, post, uploadPdfs, useResource } from "../api";
import type { Detail, Field } from "../document-types";
import { Badge, Empty, ErrorBox, Loading, Notice, Panel } from "../components";
import { Disclosure, Tabs, TabPanel } from "../ui";
import { BASE, Checklist, message, PdfPreview } from "./DocumentShared";
import {
  AutonomyEvidence,
  CorrectionTasks,
  HandoverHistory,
  PreflightView,
} from "./DocumentResolution";
import { AddressVerificationPanel } from "./AddressVerificationPanel";
import { CaseSummary } from "./CaseSummary";
import { CaseDecisionPanel } from "./CaseDecisionPanel";
import { CaseIssues } from "./CaseIssues";
import { reviewState } from "./review-state";
import { useAuth, isReviewer } from "../auth";
import { DocumentIssueReview, issueAction } from "./DocumentIssueReview";
import { groupFindings, issueTitle } from "./DocumentFindings";
export function DocumentWorkspace() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const requestedTab = params.get("tab") || "overview";
  const tab = [
    "overview",
    "review",
    "exceptions",
    "corrections",
    "automation",
    "preflight",
    "history",
  ].includes(requestedTab)
    ? requestedTab
    : "overview";
  const { session } = useAuth();
  const mutation = useRef(false);
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
    window.scrollTo({
      top: Math.max(0, top - (issueSummary.current?.offsetHeight || 0) - 16),
      behavior: "instant",
    });
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
  const chosenGroup = groups.find((group) =>
    group.items.some((item) => item.id === chosen?.id),
  );
  const relatedIssues = chosenGroup?.items || (chosen ? [chosen] : []);
  const selectedEvidence = relatedIssues.flatMap((item) => item.evidence);
  const locked = ["OPERATIONS", "CANCELLED"].includes(
    data?.submission.status || "",
  );
  async function run(action: string, note?: string): Promise<boolean> {
    if (mutation.current) return false;
    mutation.current = true;
    setBusy(action);
    setError("");
    setNotice("");
    try {
      await post(
        `${BASE}/submissions/${id}/${action}`,
        ["approve", "clear-hold", "authorize-correction"].includes(action)
          ? { revision: data!.submission.revision, ...(note ? { note } : {}) }
          : {},
      );
      await result.refresh();
      setNotice(
        action === "preflight"
          ? "Readiness snapshot saved."
          : action === "approve"
            ? "Approval and Operations handover recorded."
            : action === "clear-hold"
              ? "Investigation decision recorded. Review the updated case."
              : action === "authorize-correction"
                ? "Customer correction authorised for this revision."
                : "Validation complete. Review the updated result.",
      );
      if (action === "preflight") setParams({ tab: "preflight" });
      return true;
    } catch (error) {
      setError(message(error));
      return false;
    } finally {
      mutation.current = false;
      setBusy("");
    }
  }
  async function upload(files: File[], replace?: string) {
    if (!files.length || mutation.current) return;
    setError("");
    setNotice("");
    if (
      files.length > 10 ||
      files.some(
        (file) =>
          !/\.(pdf|jpe?g|png)$/i.test(file.name) ||
          file.size > 10 * 1024 * 1024 ||
          file.size === 0,
      )
    ) {
      setError("Choose up to 10 nonempty PDF, JPG or PNG files, no larger than 10 MB each.");
      return;
    }
    mutation.current = true;
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
      mutation.current = false;
    }
  }
  function inspect(field: Field) {
    const sourceIssue = relatedIssues.find((item) =>
      item.evidence.some(
        (source) =>
          source.document_id === field.document_id &&
          source.version_id === field.version_id,
      ),
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
    if (doc?.extraction.method === "DIGITAL_EFORM") {
      setNotice(
        "The customer must edit the digital declaration in their portal. Add supporting PDFs using Add documents.",
      );
      return;
    }
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
  const state = reviewState(data);
  return (
    <div className="rm-workspace">
      <header className="case-context">
        <div>
          <Link className="back-link" to="/submissions">
            Review queue <ArrowRight size={14} />
          </Link>
          <span className="case-reference">
            {data.submission.customer_reference}
          </span>
        </div>
        <div className="case-title-row">
          <div>
            <span className="eyebrow">
              {label(data.submission.category)} ·{" "}
              {data.submission.capture_method === "DIGITAL_EFORM"
                ? "Digital declaration"
                : "PDF submission"}
            </span>
            <h1>{data.submission.customer_name}</h1>
            <p>
              Assigned to {data.submission.owner_name} · Revision{" "}
              {data.submission.revision}
            </p>
          </div>
          <Badge value={data.submission.status} />
        </div>
      </header>
      <div className="case-workspace-grid">
        <div className="case-workspace-main">
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
          <CaseSummary
            detail={data}
            onReview={() =>
              setParams({ tab: state.open.length ? "exceptions" : "review" })
            }
            onReadiness={() =>
              setParams({ tab: locked ? "history" : "preflight" })
            }
          />
          <Tabs
            id="submission-workspace"
            className="section-nav workspace-tabs"
            label="Submission workspace"
            value={activeTab}
            items={[
              { value: "overview", label: "Overview" },
              { value: "exceptions", label: "Issues & evidence" },
              { value: "review", label: "Documents" },
              { value: "automation", label: "AI & automation" },
              { value: "preflight", label: "Handover readiness" },
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
            accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
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
            {tab === "overview" && (
              <div className="overview-sections">
                <div className="workspace-section-heading">
                  <div>
                    <h2>Request at a glance</h2>
                    <p>
                      Compare what the customer declared with recorded evidence.
                    </p>
                  </div>
                  <button
                    className="button secondary"
                    onClick={() => setParams({ tab: "review" })}
                  >
                    Inspect documents <ArrowRight size={15} />
                  </button>
                </div>
                <CaseComparison detail={data} onInspect={inspect} />
                {!!data.addressVerifications?.length && (
                  <Disclosure title="Address verification and OneMap evidence">
                    <AddressVerificationPanel
                      detail={data}
                      onInspect={inspect}
                    />
                  </Disclosure>
                )}
                <div className="verification-summary">
                  <h3>What has been checked?</h3>
                  <p>
                    {state.current
                      ? `${data.checks.filter((check) => check.passed).length} of ${data.checks.length} policy checks passed for this revision.`
                      : "Validation is not confirmed for this revision."}{" "}
                    {data.documents.length} submitted{" "}
                    {data.documents.length === 1 ? "document" : "documents"}.{" "}
                    {state.open.length
                      ? "Open findings need attention before approval."
                      : "Use the decision panel to review eligibility."}
                  </p>
                  <button
                    className="text-link"
                    onClick={() => setParams({ tab: "exceptions" })}
                  >
                    Review findings <ArrowRight size={14} />
                  </button>
                </div>
                <Disclosure title="All policy checks and results">
                  <Checklist checks={data.checks} />
                </Disclosure>
                <Disclosure title="Case reference and ownership">
                  <dl className="metadata">
                    <div>
                      <dt>Case reference</dt>
                      <dd>{data.submission.id}</dd>
                    </div>
                    <div>
                      <dt>Assigned case owner</dt>
                      <dd>{data.submission.owner_name}</dd>
                    </div>
                    <div>
                      <dt>Rule set</dt>
                      <dd>{data.ruleSet}</dd>
                    </div>
                  </dl>
                </Disclosure>
              </div>
            )}
            {tab === "review" && (
              <>
                {!locked && (
                  <div
                    className="upload-area"
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault();
                      if (!busy)
                        void upload(Array.from(event.dataTransfer.files));
                    }}
                  >
                    <UploadCloud size={22} />
                    <div>
                      <strong>
                        {data.documents.length
                          ? "Add documents"
                          : "Upload the customer forms"}
                      </strong>
                      <p>
                        Drop PDFs here, or choose files. Up to 10 MB per PDF.
                      </p>
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
                        accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
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
                    description={
                      data.submission.category === "ACCOUNT_OPENING"
                        ? "Add the customer information form, account application and tax declaration."
                        : "Submitted declarations and supporting PDFs appear here when available. Check the request status and findings before taking action."
                    }
                  />
                ) : (
                  <div className="review-layout">
                    {chosen && (
                      <div className="mobile-issue-summary" ref={issueSummary}>
                        <strong>{issueTitle(chosen)}</strong>
                        <p>{issueAction(chosen)}</p>
                      </div>
                    )}
                    <div className="preview-column" ref={preview}>
                      <Panel className="preview-panel">
                        <div className="document-toolbar">
                          <label>
                            <span className="sr-only">Document to inspect</span>
                            <select
                              aria-label="Document to inspect"
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
                                  {label(item.document_type)} | {item.filename}
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
                          {!locked &&
                            doc.extraction.method !== "DIGITAL_EFORM" && (
                              <button
                                className="button secondary"
                                disabled={!!busy}
                                onClick={chooseReplacement}
                              >
                                Replace document
                              </button>
                            )}
                        </div>
                        {version && version !== doc.version_id && (
                          <Notice>
                            You are viewing an earlier document version.
                            Findings and current values may refer to a different
                            version; check each citation before acting.
                          </Notice>
                        )}
                        {doc.extraction.requires_review && <Notice>Extraction requires human review. Compare the visible original with the extracted fields. A successful extraction does not verify document authenticity.</Notice>}
                        <PdfPreview
                          caseId={id}
                          versionId={version || doc.version_id}
                          page={previewPage}
                          pageCount={doc.versions.find(item => item.id === (version || doc.version_id))?.source_page_count || 1}
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
                            busy={!!busy}
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
                                values are hidden to avoid mixing document
                                versions.
                              </Notice>
                            ) : (
                              <dl className="field-list">
                                {doc.fields.map((field) => (
                                  <div key={field.field_name}>
                                    <dt>{label(field.field_name)}</dt>
                                    <dd>
                                      {field.field_value}
                                      <button
                                        className="text-link"
                                        onClick={() =>
                                          inspect({
                                            ...field,
                                            document_id: doc.id,
                                            version_id: doc.version_id,
                                            filename: doc.filename,
                                          })
                                        }
                                      >
                                        View source
                                        {field.page_number
                                          ? ` | page ${field.page_number}`
                                          : " | page unconfirmed"}
                                      </button>
                                    </dd>
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
                          <Disclosure title="AI extraction observations · advisory">
                            <div className="issue-context">
                              <strong>
                                AI observations - not validation findings
                              </strong>
                              <p>
                                Review these against the PDF and applicable
                                rules; they are not additional validation
                                results.
                              </p>
                              {doc.document_type === "ACCOUNT_APPLICATION" &&
                                doc.extraction.observations.some(
                                  (item) => item.field === "declaration_date",
                                ) &&
                                !data.checks.some(
                                  (check) =>
                                    check.condition_type === "DATE" &&
                                    check.field_name === "declaration_date" &&
                                    check.evidence.some(
                                      (field) => field.document_id === doc.id,
                                    ),
                                ) && (
                                  <p>
                                    No active mandatory-date check covers this
                                    account form. Resolution Management can
                                    propose the account declaration-date
                                    requirement in Settings; a different
                                    authorised approver must approve it before
                                    validation changes.
                                  </p>
                                )}
                              {doc.extraction.observations.map(
                                (observation, index) => (
                                  <p
                                    key={`${observation.field}-${observation.page}-${index}`}
                                  >
                                    {label(observation.field)} / page{" "}
                                    {observation.page}:{" "}
                                    {observation.supporting_text}
                                  </p>
                                ),
                              )}
                            </div>
                          </Disclosure>
                        )}
                      </TabPanel>
                    </Panel>
                  </div>
                )}
              </>
            )}
            {(tab === "exceptions" || tab === "corrections") && (
              <>
                {!locked &&
                  isReviewer(session) &&
                  data.submission.category === "CONTACT_UPDATE" &&
                  open.some(
                    (item) => item.check_key === "SERVICE_PHONE_REVIEW",
                  ) && (
                    <ContactPhoneReview
                      id={id}
                      revision={data.submission.revision}
                      onUpdate={() => void result.refresh()}
                    />
                  )}
                <CaseIssues
                  detail={data}
                  selected={finding}
                  onSelect={setFinding}
                  onInspect={inspect}
                  onDocuments={() => setParams({ tab: "review" })}
                />
                {selectedEvidence.filter(
                  (field) =>
                    field.version_id &&
                    data.documents.some((doc) =>
                      doc.versions.some(
                        (version) => version.id === field.version_id,
                      ),
                    ),
                ).length > 1 && (
                  <button
                    className="button secondary"
                    onClick={() => setCompare(!compare)}
                  >
                    {compare ? "Close comparison" : "Compare original PDFs"}
                  </button>
                )}
                {compare && (
                  <div className="source-comparison">
                    {selectedEvidence
                      .filter(
                        (field) =>
                          field.version_id &&
                          data.documents.some((doc) =>
                            doc.versions.some(
                              (version) => version.id === field.version_id,
                            ),
                          ),
                      )
                      .slice(0, 3)
                      .map((field, index) => (
                        <Panel
                          key={`${field.version_id}-${index}`}
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
                  title="Correction tasks and ownership"
                  open={tab === "corrections"}
                >
                  <CorrectionTasks
                    detail={data}
                    onUpload={() => setParams({ tab: "review" })}
                  />
                </Disclosure>
                <Disclosure title="All policy checks and results">
                  <Checklist checks={data.checks} />
                </Disclosure>
                <Disclosure title="Address verification and OneMap evidence">
                  <AddressVerificationPanel detail={data} onInspect={inspect} />
                  {!data.addressVerifications?.length && (
                    <p>
                      No address verification evidence was returned for this
                      case.
                    </p>
                  )}
                </Disclosure>
              </>
            )}
            {tab === "automation" && (
              <>
                <p className="context-note">
                  AI recommendations are advisory. Backend validation determines
                  eligibility and records authorised actions.
                </p>
                <Disclosure title="Risk score, contributions and decision trace">
                  <RiskInvestigation
                    detail={data}
                    onUpdate={() => void result.refresh()}
                    showActions={false}
                  />
                  {!data.riskAssessment && (
                    <p>No numerical risk assessment has been returned.</p>
                  )}
                </Disclosure>
                <AutonomyEvidence detail={data} onInspect={inspect} />
              </>
            )}
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
        <CaseDecisionPanel
          key={id}
          detail={data}
          busy={!!busy}
          onAction={run}
          onNavigate={(tab) => setParams({ tab })}
        />
      </div>
    </div>
  );
}
