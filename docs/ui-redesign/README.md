# AFI Resolve UI redesign — 14 November 2026

## Scope and evidence

Local implementation across Customer Portal and RM Dashboard. No backend, API, schema or infrastructure changes. No deployment or Git push performed. Research used frontend-design and REA skills, eight public pages with owned headless Chrome profiles, DOM and screenshots. No authenticated reference dashboards or security controls were entered. REA performed initial navigation and a bounded wait only; reference dropdown interactions, uploads, authenticated flows and reference mobile behaviour were not tested. Recommendations below are design inferences from those observations, not claims about reference production behaviour.

## Research

| Reference | Observed public pattern and application | Screenshot | REA evidence |
|---|---|---|---|
| [Wise](https://wise.com) | Large type, explicit primary action, personal/business separation. Use plain task language; do not copy green branding. | [Capture](researchWise.png) | ev_3d7d1c058a2071d975ad6340061d9af8de1c5aa3f2cc3f299d0019a5efae8708 |
| [OCBC](https://www.ocbc.com) | Audience-based navigation and a conspicuous login entry. Retain clear service grouping without official branding. | [Capture](ref0.png) | ev_c936db3a7f072c4322283e9c2c20c81b50fce75a8ff6bb121d439940ec21e472 |
| [DBS](https://www.dbs.com.sg) | Banking audiences are separated into labelled destinations; avoid mixing customer and staff journeys. | [Capture](ref1.png) | ev_4a820e3a3e4e785061407ce21b023f05bcb48f4b5ce0d16678b4219eecf078bf |
| [Mercury](https://mercury.com) | Clear primary account action and a separate demo action; keep prototype qualification visible. | [Capture](ref2.png) | ev_62bc9319d7bb074682e406ae865fa4c375394b11868d9d0a4d27da6caabb70ed |
| [Stripe](https://stripe.com) | Organised product navigation and differentiated primary/secondary actions; omit decorative gradient treatments in case work. | [Capture](ref3.png) | ev_594389eaa366ca289d3ca64176255149fd8ba8803c30bdc4db3b0cfb375c1915 |
| [Linear](https://linear.app) | Public product illustration exposes workspace navigation and a detailed issue surface; use a stable shell and compact case content. | [Capture](ref4.png) | ev_e9e426e506fc124b37786a9c2ec01c8b47c1dbbb2b350dc2331d4dc2c088158f |
| [Vercel](https://vercel.com) | Grouped navigation and explicit login/sign-up destinations; apply precise structure rather than copy its branding. | [Capture](ref5.png) | ev_b6b744fe1f455d53f37ddc6d10f4d811127b5e3c84fc48b8eba9c3049427956a |
| [SGDS](https://designsystem.tech.gov.sg) | Foundations, components and tokens are first-class navigation; centralise control sizing, focus and colour roles. | [Capture](ref6.png) | ev_def854fefeb44e28297f382244464aed27552311296f471c05d2b5a85d69bf1f |

## Audit and design plan

P0: remaining Reviewer prose and blank login default; eForm sent directly without a review stage; account-opening empty-state instructions shown on service cases. Fixed.

P1: portal uses Open Sans while staff uses Inter; multiple theme layers have inconsistent radii, spacing and focus treatments. Introduced identical premium.css presentation files loaded last in each app, retaining prior responsive and behavioural layouts. Added 44px form/button sizing, 8px control/12px panel radii, 4px spacing unit, tabular metrics, restrained borders/shadows, readable table rows, stronger focus outlines and reduced-motion support. Existing PDF upload, drag/drop, file checks, processing feedback, retry handling, case preview, citations and correction timelines were reused.

P1: queue supports search/status/source but lacks request-category filters and sorting. Added real category choices from returned cases and newest/oldest/customer sorting; no fabricated priority field or metrics.

Design direction: AFI navy #172b46, action blue #294db5, canvas #f4f6fa, surface #ffffff, text #34465c, muted #586a80. Segoe UI Variable Text/Segoe UI/system sans avoids additional network fonts; 26–34px page headings, 18px section headings, 14px inputs. Left-aligned content and a stable navigation rail. Customer layout: navigation | task details | contextual evidence guidance. Staff layout: navigation | queue/filter strip or case summary | document/findings workspace. Preserve blue identity rather than copying red bank branding. Review of the plan rejected ornamental gradients and repeated identical metric decoration; the meaningful visual accent is the task/progress state.

## Implemented

- Identical shared token/presentation files at both frontends' src/premium.css; imported after prior themes. Dark-theme token overrides retained for staff preferences.
- Consistent panels, controls, table rows, sidebar selection, feedback and responsive form geometry.
- Subtle modal/dropdown entrance and skeleton-style loading accent; reduced motion disables transitions/animation. No fabricated numeric upload progress.
- Existing-customer eForms now have Details → Review and send. Native required fields validate before review; inactive details are disabled and hidden during review. Edit details returns to preserved state. Original API payload, declarations, revision guard and uncertain-outcome retry remain intact. Legacy account-opening eForm remains on its existing branch.
- RM login defaults to reviewer and displays Resolution Management Workspace. Remaining role prose updated; REVIEWER authority, username and CSS/helper identifiers retained. No Operator login option introduced.
- Queue request-type filtering and sorting, reset pagination on changes, filtered empty-state semantics retained.
- Service-specific document empty-state copy.

## Before and after

| Area | Before | After |
|---|---|---|
| Customer form | Immediate submit after entry | Explicit review stage and edit action |
| Visual system | Different typography/control geometry | Shared presentation tokens, larger controls, consistent panels |
| Case queue | Search/status/source | Adds actual request types and three sort orders |
| Role labels | Secondary Reviewer prose; blank default | Resolution Management prose; reviewer account selected |
| Empty documents | Account-opening instructions on service cases | Contextual guidance based on case category |

[Before desktop](before-desktop.png) · [Before mobile](before-mobile.png)

[After customer form desktop](after-service-request-desktop.png) · [After mobile](after-service-request-mobile.png) · [After tablet](after-service-request-tablet.png)

[RM desktop](after-rm-1440.png) · [RM tablet](after-rm-768.png) · [RM mobile](after-rm-390.png) · [RM login](rm-login.png)

Screenshots show synthetic fixture identities only; no live customer records were accessed. Before screenshots cover the customer form, not a complete before inventory of every page. RM captures show a controlled case with no PDF documents, not evidence of live extraction.

## Verification

- Both npm run build commands pass TypeScript and Vite production builds.
- node customer-portal/scripts/redesign-ui-smoke.cjs passes against built assets and intercepted fixture APIs. Checks existing profile prefill, request selection, conditional tax fields, address declaration, review-before-send, submission payload, RM login/approval, customer status refresh and no JS page errors. No horizontal overflow at 1440/768/390 for captured case views and customer form. This script uses the existing ignored browser Playwright installation; no application dependency added.
- REA independently captured the real local built RM login with complete capture evidence ev_b7b30fd9fc5e444a9a62df37c36d866d6ad2b39c73432ed2870a2932afd2e110. Its temporary static server was stopped.
- git diff --check passes both repositories.

## Remaining limitations and next checks

This is a cohesive presentation and targeted workflow improvement, not proof of production readiness. Auth/approval in the browser smoke are fixture-backed. Real PDF selection/upload, OTP, live backend approval permissions, Gemini, SMTP, S3/RDS, full customer correction/revalidation and AWS integration were not exercised this turn. Historical account-opening remains in source but was not functionally retested. The queue's new filters/sorts compile but need a multi-case browser scenario. Keyboard/screen-reader testing, measured WCAG contrast audit and all-page mobile coverage remain outstanding; do not claim WCAG certification. Reference captures are first view only; no private product behaviours were inferred as verified facts. Existing theme files remain; premium.css deliberately controls shared presentation without broad refactoring. Keep its two copies synchronised. New screenshots and changes are local and await review before any production deployment.

