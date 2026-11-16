# AFI Resolve 2.0: operational UI and evidence audit

Scope: Resolution Management, document review, customer portal, their API presentation, and the document investigation service. Changes are local; no AWS deployment or policy migration was performed.

## Findings and implemented changes

| Problem | Implemented outcome |
| --- | --- |
| A manager had to open several views to understand a case. | Case summary shows the customer request, current check count, open issues, highest recorded severity, actual correction instruction, reason, assigned roles and workflow stages. |
| Cases awaiting checks could appear clear because they had no findings. | Unvalidated revisions explicitly require checking. Empty findings and absent customer requests no longer imply successful validation. |
| Important work appeared below recent submissions. | Action-required cases appear first on the overview. Case queues default to action-required cases first, with a newest-first option. |
| The queue lacked validation context and a concrete next step. | Read-only API fields provide current-revision check counts, open severities and the first recorded correction instruction. Repeated validations are deduplicated by check key. |
| Document selection concealed filenames and source navigation was fragile. | Selectors include filenames and versions. Evidence links select the cited version and page; page changes remount the PDF viewer. Desktop retains the PDF beside findings; smaller screens stack them. Earlier versions show a warning. |
| Findings did not clearly explain failure, policy, ownership or document requirements. | A policy result block shows the recorded failure, rule description/version and task assignment. Attachment rules expose their configured document category. A generic supporting-document rule is explicitly identified as having no specified subtype. |
| No citations were treated as proof that a document was missing. | The UI and investigation fallback distinguish absent evidence from an established policy requirement. |
| Historical investigation output could look current. | Package/rule-set differences and resolved findings mark recommendations as historical. Older records without rule-set metadata disclose that limitation. New investigation records retain the rule-set snapshot. |
| AI explanation input lacked the actual correction and policy context. | Spring supplies rule description, document category, condition and correction instruction. The model prompt limits requirements to this supplied policy context. |
| AI narrative and policy decisions were difficult to distinguish. | Gemini commentary is advisory. Rule-based fallbacks explicitly identify the absence of model-generated analysis. Structured sections show source citations, available field comparisons, limitations and policy next steps. Existing citation/action validation remains enforced in both services. |
| Investigation evidence was not actionable from the automation view. | Supplied, accessible document citations link back to the exact PDF version/page. Repository-only records do not receive unauthorised PDF links. |
| Customer requests lacked an explanation and responsible role. | The customer DTO adds only the recorded reason, rule name, assigned role and policy-defined attachment category. Existing customer ownership checks and document access boundaries remain in place. |
| Workflow stages could imply an approval that had not occurred. | Submission, extraction, validation, resolution, readiness and Operations have explicit recorded/unknown states. Neither readiness nor handover claims account-opening approval. The customer journey does not invent individual extraction completion. |
| Small type, inconsistent labels and weak hierarchy slowed scanning. | Both applications load identical operational CSS: shared typography, contrast tokens, controls, focus indicators, spacing, semantic colours and responsive layouts. Status labels match across applications. Repeated overview detail is collapsed. |

## Boundaries retained

- The document API does not supply a numerical risk score. The UI says **Not provided**, while showing recorded policy severity separately. No score or risk formula was created or changed; legacy transaction scoring is untouched.
- Supporting-document subtypes are shown only when defined by the recorded policy. The existing generic supporting-document rule is not converted into a passport, proof-of-address or other invented requirement.
- Spring Boot still owns validation, allowed actions, preflight, independent verification, routing and permissions. The changes do not add an approval bypass or alter rule evaluation.
- Existing passwordless demo access from the preceding task is retained; this audit does not change authentication configuration.
- Customer APIs remain restricted to owned cases. No PDF download capability or internal investigation data was added to customer endpoints.
- Gemini prose is advisory, even when citation identifiers pass validation. Identifier checks cannot prove every natural-language statement; source review remains necessary.

## Verification

Both frontend production builds passed. Seven Resolve tests cover case-state semantics and finding grouping; three portal tests cover upload retry boundaries. Twenty-eight Python tests cover extraction grounding and investigation fallback/citation rejection. Local browser checks use synthetic fixtures to cover desktop/mobile layout, policy context, historical investigation warnings, evidence navigation, profile selection, mobile navigation and the correction dialog. Small screens offer an optional inline PDF preview and a full-PDF link instead of forcing a tall embedded viewer. These fixtures verify presentation, not live banking data or provider performance.

Backend Java execution requires Java 21 and Maven, unavailable in the current shell. Backend changes therefore still require compilation and integration checks in a provisioned environment. No live Gemini call or AWS deployment was performed. The five-second comprehension target has not been measured with banking staff.
