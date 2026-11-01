# Workflows and presenter guide

## Main demonstration

1. Open the local dashboard and sign in as the reviewer (`reviewer` / `reviewer-demo`). These are documented demo credentials, configurable through environment variables.
2. In Settings, reset the synthetic data. Historical audit events remain available.
3. Trigger the partial failure. The default fixture writes 1,000 transaction records, with 800 already processed and 200 pending.
4. Run detection. It queries the records and produces a partial batch case; repeating detection does not duplicate it.
5. Open the case and start investigation. The UI exposes workflow state while the backend obtains an explanation. With no Gemini key, the result explicitly says **Deterministic fallback**.
6. Open Digital Twin and run simulation. The unguarded full retry predicts 800 duplicate attempts and is rejected. Pending-only recovery predicts 200 affected records and 1,000 completed records with no duplicates.
7. Execute selective recovery. Spring locks the batch, checks the current source hash and policy independently, then updates only pending synthetic records.
8. Inspect verification: expected record count, completed/pending counts, processing uniqueness and reconciliation integrity. The case becomes RESOLVED only if these pass.
9. Open the audit history to show detection, investigation, simulation, policy, execution and verification events.

All numbers on the UI originate from backend records. The automated acceptance script repeats this workflow with a 17-record batch to demonstrate that 800/200 is not built into the engine.

## Human review demonstration

The auxiliary fixtures contain duplicate reconciliation references and inconsistent reconciliation evidence. Open one of those cases, investigate and simulate. It requires additional evidence and manual review, never an automatically executable financial correction.

An operator cannot submit approval: Spring returns HTTP 403. A reviewer can approve or reject a `REQUEST_MANUAL_REVIEW` handoff and supply a reason. Even an approved handoff leaves the exception unresolved. This demonstrates that a human approval cannot override data invariants.

## Repeatability and failures

- Use Settings to repeat the demo without editing tables.
- Duplicate recovery requests using the same idempotency key return the original action.
- A changed source snapshot requires investigation/simulation again; a stale prediction cannot authorize execution.
- An unavailable Gemini provider uses the labelled fallback. An unavailable Python simulator prevents recovery.
- A failed verification retains an unresolved failure state and its evidence.
- Audit history persists across resets; the dataset reset is itself an audit event.

## Automated demonstration

From the repository root, while the demo stack is running:

```sh
python scripts/demo/acceptance.py --base-url http://localhost:5173
```

This command intentionally resets only the isolated synthetic demo records. It checks actual HTTP authentication, detection deduplication, fallback/live method labelling, simulation immutability, unsafe retry rejection, concurrent idempotency, persisted verification, audit events and manual escalation. It leaves a verified primary demonstration in the database for inspection.
