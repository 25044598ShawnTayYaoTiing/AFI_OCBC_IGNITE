# RM review workspace redesign

Implemented 17 November 2026. This is a frontend redesign using the existing APIs and backend policies.

## Original problems

The live case view repeated customer details and status in a dossier, heading and summary. Address verification, risk calculations and investigation history preceded the review tabs. Reviewers had to scroll through technical details before seeing documents or understanding individual correction tasks. The previous [UX audit](ui-ux-audit.md) remains useful historical context; its statements about absent numerical risk scores predate the current case-risk implementation.

## Information architecture

The global navigation provides the review queue, cases assigned to the signed-in staff identity, customer corrections, cases ready for approval, completed handovers, and oversight tools. These are views of the existing authorised case list, not new permissions.

Each case has one customer heading, request type, reference and revision. A plain-language outcome leads the review: approval blocked, revalidation needed, investigation hold, eligible for approval, or handover recorded.

| Area | Purpose |
| --- | --- |
| Overview | Compare stored-profile evidence, customer declarations and supporting evidence for relevant fields. Additional fields and policy details expand on demand. |
| Issues & evidence | Select a finding and see the problem, required action, expected/submitted values where returned, source citations, task ownership and recorded status together. Resolved findings are optional. |
| Documents | Authenticated PDF preview beside findings on wider screens, document/version selection, source-page navigation, replacement/upload controls and extracted fields. Earlier versions explicitly hide current extracted values. |
| AI & automation | Existing investigation and automation records; risk contributions and decision trace expand on demand. AI recommendations remain advisory. |
| Handover readiness | Existing backend preflight and simulation results. A simulation is not an executed action. |
| History | Recorded handovers and the existing audit history. |
| Decision panel | Current policy blockers, customer correction count, revision state, revalidation and authorised reviewer decisions. Sticky on desktop; compact and before the tabs on mobile. |

The interface uses restrained red accents, readable type, larger controls, keyboard-operable tabs and the existing native modal dialog with focus containment and restoration. Small screens stack the workspace and keep horizontal scrolling inside the comparison table, rather than the page.

## Implementation and decision safeguards

`DocumentWorkspace` retains the upload, version, PDF, evidence, preflight, investigation and history capabilities. `CaseIssues` unifies issue review. `CaseComparison` presents returned source values without inventing matches or missing values. `reviewState` derives presentation eligibility, and `CaseDecisionPanel` places approval, correction authorisation and hold clearance together.

Approval requires current nonempty validation results, no failed blocking checks, no open findings, no hold and an eligible recorded backend status. Reviewer permissions are required for reviewer decisions. Confirmation dialogs explain the consequence and carry the current revision to the existing API. Duplicate-submit guards cover case mutations and decision confirmation. Server errors remain visible; the backend checks eligibility again and retains decision authority. Independent phone verification also requires confirmation.

No general case-rejection endpoint was added. The existing address-change review retains its separate approve/reject workflow. No backend contracts, migrations, OneMap integration, Gemini configuration or AWS infrastructure were changed. The two OneMap validation rules remain DRAFT.

## Screenshots

The before screenshots show the original deployed interface. The after screenshots show the redesigned local frontend connected to the actual deployed backend, with synthetic cases and real authenticated PDF bytes. They do not contain login credentials.

| View | Before | After |
| --- | --- | --- |
| Desktop case review | [Original](rm-redesign/before-desktop.png) | [Overview](rm-redesign/after-desktop.png), [Issues](rm-redesign/after-issues-desktop.png) |
| Mobile case review | [Original](rm-redesign/before-mobile.png) | [Issues and decision controls](rm-redesign/after-mobile.png) |
| Desktop document review | Original viewer was beneath the stacked summaries | [Document and findings workspace](rm-redesign/after-documents.png) |

## Verification

The production TypeScript/Vite build passed. Browser regression checks cover 1440, 1280, 768 and 390 px layouts without horizontal page overflow; missing supporting evidence; high-risk conflicts; no-issue approval eligibility; completed handover; evidence-page links; historical document versions; stale-revision blocking; reviewer-role guards; correction-note requirements; modal focus and cancellation; backend rejection; and duplicate approval prevention. Mutations in these regression checks are intercepted fixtures, not live decisions.

Separate browser checks connect the new frontend to the deployed backend and verify actual case rendering, authenticated PDF content and rendering, ready/assigned queue views, unchanged OneMap draft rules, and login/request history in the existing Customer Portal. A dedicated synthetic savings-account request was prepared for post-deployment approval verification; it does not open a real bank account.

To repeat the UI regression checks, make Playwright available in the frontend or adjacent Customer Portal checkout, provide captured synthetic case details named `missing`, `conflict`, `completed` and `ready`, and run:

```powershell
node frontend/scripts/rm-review-check.cjs .local/rm-redesign/fixtures.json http://127.0.0.1:5185 .local/rm-redesign
```

Private fixture captures and credentials remain ignored. The test script intercepts case decisions and uses a checked-in synthetic PDF for its document response.

## Limits

The five-second comprehension target has not been measured with banking staff. Native PDF rendering and mobile PDF controls vary by browser; authenticated open/download links remain available. Expected profile values are shown only when returned by existing APIs. Real email/SMS delivery and new live Gemini investigations were not exercised by this frontend-only redesign.

Release completion, deployed screenshots and the live synthetic approval result are reported with the delivery message after CI/CD and post-deployment verification.
