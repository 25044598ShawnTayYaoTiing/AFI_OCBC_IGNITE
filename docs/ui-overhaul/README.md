# AFI Resolve — structural UI overhaul

14 November 2026. Local implementation only; no commit, push, deployment, infrastructure or backend change.

## Direction and research

The earlier pass retained the old composition. This pass replaces the customer navigation, home, request portfolio, message-centre structure, form staging, staff home and case workspace composition. frontend-design and REA were used. Prior REA captures of OCBC, Wise, Mercury, Stripe, Linear and Vercel are retained with observations in [the first research report](../ui-redesign/README.md). They were reused rather than represented as new authenticated research.

New REA public captures: [Notion](https://www.notion.so) ([image](overhaul-ref0.png), ev_32d94cd88bcd079b980927b422f720e898bee69022b6b3b180b6e2dc63f3a85a) and [Apple](https://www.apple.com) ([image](overhaul-ref1.png), ev_9622f07590b5485371248b30ba60b283d090d1e694d33a4ade6bd79f0b19920a). Notion visibly separates the public pitch from a workspace illustration; Apple uses a tightly constrained navigation bar and a single product/story per section. These observations support distinct information zones and a smaller number of primary actions. Reference inspection covers public first views, not private bank dashboards or authenticated interactions. No controls or authentication barriers were bypassed. Suggestions about authenticated workflows are design inferences, not verified reference functionality.

Visual system: graphite #202622, warm canvas #f5f4f0, white #ffffff, signal red #b52f35, ink #38433d and muted #637067. System Segoe UI Variable/Segoe UI avoids a remote font dependency. Strong 42–64px landing typography, 28–32px working headings, 17–19px sections. Red signals primary actions, not verification success; green remains semantic success. No official OCBC assets or copied layouts.

Plan critique: rejected keeping the white sidebar in both applications, another four-card KPI customer home, and a long undifferentiated eForm. Customer navigation moves horizontally on desktop; the staff shell stays compact and dark. Customer home prioritises services and actions rather than analytics. The case dossier is a persistent navigation/context surface, not another metric tile.

## Implementation

| Surface | Change |
|---|---|
| Customer shell | Horizontal desktop services bar; preserved mobile drawer/focus behaviour. New Resolve wordmark text and red accent. |
| Customer authentication | Large editorial introduction with independent floating sign-in surface; preserved real auth handler. |
| Customer home | Service launcher, customer-reference summary, action inbox, updates and request records. All counts derive from returned submissions; unavailable data uses a dash. |
| Service selection | Two broad editorial method rows instead of conventional equal cards. PDF and digital routes retained. |
| Digital request | Choose service → Your changes → Review and send. Contextual profile moved into a separate side surface. Service-specific fields, native required validation, prefill, edit/retry/revision controls and historical account-opening branch retained. Home service links initialise the correct request type. |
| Request history | Date-grouped visual request portfolio preserving search/status filters/sort and detail links. |
| Notifications | Message centre with category navigation plus original list/filter/refresh; no invented unread state. |
| Customer progress/corrections | Strong request-summary band, journey rail and response-card treatments; original correction dialog and submission links retained. |
| Staff shell/login | Graphite rail, command centre/case queue/completed handover labels, redesigned sign-in. Only Resolution Management role copy; reviewer remains the configured username and REVIEWER backend authority. Historical owner labels are mapped in presentation without changing records. |
| Staff overview | Command heading and linked focus queue, then real operational metrics, requests, attention and activity. Missing analytics remain unavailable. |
| Case centrepiece | Persistent dossier with identity, revision freshness, evidence-library navigation and check/history links. Three-source comparison shows existing synthetic bank-profile evidence, declaration values and supporting PDF fields from the actual detail payload. Unavailable sources are explicitly labelled. Original document selector/version/preview and findings panel stay together, with existing policy approval, correction, automation, preflight and history controls. |
| Supporting screens | Shared controls, tables, feedback, panel geometry, theme and responsive styles apply to reports, audit and settings; their functional structures were retained rather than separately rebuilt. |
| Motion/accessibility | Short page/modal entrances, hover feedback, loading skeleton accent, focus outlines, 44–46px primary controls; reduced motion disables animation and transitions. |

Shared design source: identical src/premium.css in both frontends. Existing base CSS remains for proven utilities and layouts; this file owns the final presentation. New case comparison component is frontend-only. No new application package dependency or schema.

## Visual comparison

Before files are the previous conservative redesign's captures, not original production screenshots. The old case fixture contained no documents; the new fixture includes one synthetic declaration to exercise the workspace, so compare composition rather than case counts.

| Screen | Before | New desktop | New mobile |
|---|---|---|---|
| eForm | [Before](before-service-request-desktop.png) | [After](after-service-request-desktop.png) | [390px](after-service-request-mobile.png) |
| Case workspace | [Before](before-rm-1440.png) | [After](overhaul-case-1440.png) | [390px](overhaul-case-390.png) |
| Customer home | Previous shell visible in before eForm | [New](overhaul-home-1440.png) | [390px](overhaul-home-390.png) |
| Staff home | Previous structure retained in Git source | [New](overhaul-command-1440.png) | Case/mobile shell verified separately |
| Request portfolio | Previous table in Git source | [New](overhaul-history-1440.png) | [390px](overhaul-history-390.png) |
| Message centre | Previous table/list in Git source | [New](overhaul-notifications-1440.png) | [390px](overhaul-notifications-390.png) |
| Staff login | Previous capture in first report | [New](overhaul-login-1440.png) | [390px](overhaul-login-390.png) |

Additional 768px screenshots are in this directory. All identities are explicitly synthetic UI fixtures. Native Chrome PDF preview is dark/blank in the headless screenshots; they establish the surrounding workspace, not successful visual rendering of PDF page content. The PDF response, iframe path and download surface are preserved; native rendering needs a headed-browser follow-up.

## Essential checks

- Both TypeScript + Vite production builds passed after final implementation.
- node customer-portal/scripts/redesign-ui-smoke.cjs passed against built assets and controlled fixture APIs. Covered customer/staff sign-in, client navigation, three request types, prefill, conditional foreign-tax inputs, native declaration, step transitions, review/send payload, multipart PDF-selection/upload request and confirmation, case filter/sort, approval and customer status refresh. Fixture PDF is tracked under scripts/fixtures.
- Browser screenshots and no-horizontal-overflow assertions at 1440/768/390 for customer home, eForm, request detail, history, notifications, profile, correction inbox and staff case workspace. Login captured at all widths; staff command centre captured at desktop. No JS page errors in the scenario.
- REA independently observed the running local Vite login pages: staff ev_e9fba43068ad2a2b3d4e8da901717ae575aebaa9576fa737bdd8723aad81c46d, customer ev_9ccc5d7c9595ce7968d8d8498da2aea47919ad952c780a39106cbcb3039f9aaa. [Staff capture](overhaul-local-staff.png), [customer capture](overhaul-local-customer.png). Owned REA browser processes cleaned up. Vite remains running at localhost:5173 and localhost:5174.
- git diff --check passed both repositories. No backend tests rerun because backend code was unchanged.

## Limits and remaining verification

Local backend/database were not started. Fixtures are not proof of live auth/permissions, API compatibility, Gemini accuracy, SMTP delivery, RDS/S3 persistence, real customer correction/revalidation or AWS operation. No real email or customer records accessed. This is frontend verification only. Full keyboard/screen-reader and contrast measurement, all staff report/settings layouts at every breakpoint, dark-theme audit and headed PDF rendering remain follow-up checks. Staff reports/audit/settings retain their original component structures with the new common system; no claim of independent structural replacement for every legacy tool. Research did not inspect authenticated reference dashboards or exhaustively test their interactions.

The redesign is visibly structural in its principal journeys; deployment and real full-stack demonstration readiness are separate gates. Nothing was pushed or deployed. Existing local frontends hot-reload these changes; credentials and submitted data still require the shared backend.

