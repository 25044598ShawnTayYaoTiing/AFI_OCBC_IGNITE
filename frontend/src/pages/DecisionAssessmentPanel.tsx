import type { DecisionAnswer, DecisionAssessment } from "../document-types";

const QUESTION_LABELS: Record<string, string> = {
  classification: "Document type",
  readable: "Readability",
  review_route: "Review route",
  consistency: "Consistency",
  service_relevance: "Service relevance",
  holder_matches_profile: "Customer match",
  request_corroboration: "Request match",
};

const OPTION_LABELS: Record<string, string> = {
  continue_checks: "Continue checks",
  clearer_copy: "Clearer copy needed",
  manual_review: "Manual review",
  consistent: "Matches profile & request",
  address_differs: "Address / unit differs",
  tax_differs: "Tax details differ",
  identity_differs: "Holder identity differs",
  incomplete: "Insufficient details",
};

const GATING_KEYS = new Set([
  "classification",
  "readable",
  "review_route",
  "consistency",
  "service_relevance",
]);

const ADVISORY_KEYS = new Set([
  "holder_matches_profile",
  "request_corroboration",
]);

export function answerConfidence(answer?: DecisionAnswer | null): number | null {
  if (!answer) return null;
  if (typeof answer.confidence === "number" && Number.isFinite(answer.confidence)) {
    return answer.confidence;
  }
  if (answer.type === "noul" && typeof answer.noul === "number" && Number.isFinite(answer.noul)) {
    return Math.max(answer.noul, 1 - answer.noul);
  }
  return null;
}

export function gatingPassProbability(decision?: DecisionAssessment | null): number | null {
  if (!decision || decision.outcome === "UNAVAILABLE" || decision.unavailable) return null;
  if (typeof decision.gating_pass_probability === "number" && Number.isFinite(decision.gating_pass_probability)) {
    return decision.gating_pass_probability;
  }
  const gateProbs = Object.values(decision.gate_probabilities ?? {}).filter(
    (value): value is number => typeof value === "number" && Number.isFinite(value),
  );
  if (gateProbs.length) return Math.min(...gateProbs);
  if (typeof decision.gating_min_confidence === "number" && Number.isFinite(decision.gating_min_confidence)) {
    return decision.gating_min_confidence;
  }
  if (typeof decision.min_confidence === "number" && Number.isFinite(decision.min_confidence)) {
    return decision.min_confidence;
  }
  return null;
}

export function modelMinConfidence(decision?: DecisionAssessment | null): number | null {
  if (!decision || decision.outcome === "UNAVAILABLE" || decision.unavailable) return null;
  if (typeof decision.model_min_confidence === "number" && Number.isFinite(decision.model_min_confidence)) {
    return decision.model_min_confidence;
  }
  const gatingValues = Object.entries(decision.answers ?? {})
    .filter(([key]) => GATING_KEYS.has(key))
    .map(([, ans]) => answerConfidence(ans))
    .filter((value): value is number => value !== null);
  return gatingValues.length ? Math.min(...gatingValues) : null;
}

export function minimumDecisionConfidence(decision?: DecisionAssessment | null): number | null {
  return gatingPassProbability(decision);
}

export function advisoryDecisionConfidence(decision?: DecisionAssessment | null): number | null {
  if (!decision || decision.outcome === "UNAVAILABLE" || decision.unavailable) return null;
  if (typeof decision.advisory_min_confidence === "number" && Number.isFinite(decision.advisory_min_confidence)) {
    return decision.advisory_min_confidence;
  }
  const advisoryValues = Object.entries(decision.answers ?? {})
    .filter(([key]) => ADVISORY_KEYS.has(key))
    .map(([, ans]) => answerConfidence(ans))
    .filter((value): value is number => value !== null);
  return advisoryValues.length ? Math.min(...advisoryValues) : null;
}

function confidenceTone(confidence: number, threshold = 0.8): "high" | "medium" | "low" {
  if (confidence >= threshold) return "high";
  if (confidence >= 0.6) return "medium";
  return "low";
}

function formatOption(key: string): string {
  return OPTION_LABELS[key] ?? key.replace(/_/g, " ");
}

function pct(value: number, digits = 0): string {
  return `${(value * 100).toFixed(digits)}%`;
}

function ProbabilityBars({ answer }: { answer: DecisionAnswer }) {
  if (typeof answer.noul === "number") {
    const pYes = Math.max(0, Math.min(1, answer.noul));
    const pNo = 1 - pYes;
    return (
      <div className="d1-bars">
        {[
          { key: "Yes", value: pYes, primary: pYes >= 0.5 },
          { key: "No", value: pNo, primary: pYes < 0.5 },
        ].map((item) => (
          <div key={item.key} className="d1-bar-row">
            <span className={item.primary ? "d1-bar-label is-top" : "d1-bar-label"}>{item.key}</span>
            <div className="d1-track">
              <div className={item.primary ? "d1-fill is-top" : "d1-fill"} style={{ width: `${item.value * 100}%` }} />
            </div>
            <span className="d1-pct">{pct(item.value, 1)}</span>
          </div>
        ))}
      </div>
    );
  }

  const probs = Object.entries(answer.probabilities ?? {});
  if (!probs.length) return null;

  const isScore = typeof answer.score === "number" || answer.type === "score";
  const sorted = isScore
    ? probs.sort((a, b) => Number(a[0]) - Number(b[0]))
    : probs.sort((a, b) => b[1] - a[1]).slice(0, 4);
  const topKey = sorted.reduce((best, cur) => (cur[1] > best[1] ? cur : best), sorted[0])[0];

  return (
    <div className="d1-bars">
      {sorted.map(([option, probability]) => {
        const isPrimary = answer.choice ? option === answer.choice : option === topKey;
        const label = isScore && answer.legend?.[option]
          ? `${option} · ${answer.legend[option]}`
          : formatOption(option);
        const clamped = Math.max(0, Math.min(1, Number(probability) || 0));
        return (
          <div key={option} className="d1-bar-row">
            <span className={isPrimary ? "d1-bar-label is-top" : "d1-bar-label"} title={label}>
              {label}
            </span>
            <div className="d1-track">
              <div className={isPrimary ? "d1-fill is-top" : "d1-fill"} style={{ width: `${clamped * 100}%` }} />
            </div>
            <span className="d1-pct">{pct(clamped, 1)}</span>
          </div>
        );
      })}
    </div>
  );
}

function answerHeadline(answer: DecisionAnswer): string {
  if (typeof answer.noul === "number") return answer.noul >= 0.5 ? "Yes" : "No";
  if (answer.choice) return formatOption(answer.choice);
  if (typeof answer.score === "number") {
    const levels = Object.keys(answer.probabilities ?? {}).length;
    const maxLevel = levels > 1 ? levels - 1 : 2;
    return `${answer.score.toFixed(2)} / ${maxLevel}`;
  }
  return "Assessed";
}

export function DecisionAssessmentPanel({
  decision,
  currentRevision,
}: {
  decision: DecisionAssessment;
  currentRevision?: number;
}) {
  const threshold = decision.threshold ?? 0.8;
  const unavailable = decision.outcome === "UNAVAILABLE" || Boolean(decision.unavailable);
  const staleForRevision =
    !unavailable &&
    (Boolean(decision.stale) ||
      (typeof currentRevision === "number" &&
        typeof decision.context_revision === "number" &&
        decision.context_revision !== currentRevision));
  const passProb = staleForRevision ? null : gatingPassProbability(decision);
  const modelConf = staleForRevision ? null : modelMinConfidence(decision);
  const advisoryConf = staleForRevision ? null : advisoryDecisionConfidence(decision);
  const passBand = passProb !== null ? confidenceTone(passProb, threshold) : null;
  const modelBand = modelConf !== null ? confidenceTone(modelConf, threshold) : null;
  const advisoryBand = advisoryConf !== null ? confidenceTone(advisoryConf, threshold) : null;
  const entries = Object.entries(decision.answers ?? {});
  const failedSet = new Set(decision.failed_criteria ?? []);
  const gateFailures = decision.gate_failures ?? [];

  return (
    <section className="d1-panel" aria-label="Decision 1 assessment">
      <div className="d1-header">
        <div>
          <strong className="d1-title">
            {unavailable
              ? "AI evidence assessment unavailable"
              : staleForRevision
                ? "AI evidence assessment stale (refresh pending)"
                : decision.outcome === "PASSED"
                  ? "Decision 1 verified"
                  : "Decision 1 flagged for review"}
          </strong>
          <span className="d1-meta">
            {decision.model ?? "gemini-decision-1"} · Threshold {pct(threshold)}
            {typeof decision.context_revision === "number" ? ` · Rev ${decision.context_revision}` : ""}
            {typeof decision.elapsed_ms === "number" ? ` · ${(decision.elapsed_ms / 1000).toFixed(2)}s` : ""}
            {typeof decision.usage?.total_input_tokens === "number"
              ? ` · ${decision.usage.total_input_tokens.toLocaleString()} tok`
              : ""}
          </span>
        </div>
        {!unavailable && !staleForRevision && (
          <div style={{ display: "flex", gap: "0.4rem", flexWrap: "wrap", justifyContent: "flex-end" }}>
            {passProb !== null && passBand && (
              <span className={`d1-conf-pill is-${passBand}`}>
                Pass probability {pct(passProb, 1)}
              </span>
            )}
            {modelConf !== null && modelBand && (
              <span className={`d1-conf-pill is-${modelBand}`}>
                Model confidence {pct(modelConf, 1)}
              </span>
            )}
            {advisoryConf !== null && advisoryBand && (
              <span className={`d1-conf-pill is-${advisoryBand}`}>
                Advisory match {pct(advisoryConf, 1)}
              </span>
            )}
          </div>
        )}
      </div>
      {unavailable ? (
        <p className="field-hint" style={{ marginTop: "0.35rem" }}>
          AI evidence assessment could not be refreshed{decision.previous_outcome ? ` (previous ${decision.previous_outcome} invalidated)` : ""}. Deterministic checks and manual review remain authoritative.
        </p>
      ) : staleForRevision ? (
        <p className="field-hint" style={{ marginTop: "0.35rem" }}>
          Context assessment is from an earlier revision and awaiting refresh. Deterministic checks remain authoritative.
        </p>
      ) : null}
      {!unavailable && !staleForRevision && (gateFailures.length > 0 || failedSet.size > 0) && (
        <p className="field-hint" style={{ marginTop: "0.35rem" }}>
          Failed criteria:{" "}
          {gateFailures.length > 0
            ? gateFailures
                .map((f) => `${QUESTION_LABELS[f.criterion] ?? formatOption(f.criterion)} (${pct(f.actual, 1)} vs ${pct(f.threshold, 0)})`)
                .join("; ")
            : Array.from(failedSet)
                .map((c) => QUESTION_LABELS[c] ?? formatOption(c))
                .join(", ")}
        </p>
      )}

      {!unavailable && entries.length > 0 && (
        <details className="d1-details" open={!staleForRevision && decision.outcome !== "PASSED"}>
          <summary>
            Probability breakdown ({entries.length} checks{staleForRevision ? ", prior revision" : ""})
          </summary>
          <div className="d1-grid">
            {entries.map(([key, answer]) => {
              const conf = answerConfidence(answer);
              const gateProb = typeof decision.gate_probabilities?.[key] === "number"
                ? decision.gate_probabilities[key]
                : null;
              const failed = failedSet.has(key);
              const tone = failed
                ? "low"
                : gateProb !== null
                  ? confidenceTone(gateProb, threshold)
                  : conf !== null
                    ? confidenceTone(conf, threshold)
                    : "low";
              const roleTag = ADVISORY_KEYS.has(key) ? "Advisory" : "Gating";
              return (
                <div key={key} className="d1-card">
                  <div className="d1-card-top">
                    <span className="d1-q">
                      {QUESTION_LABELS[key] ?? formatOption(key)} · <small>{roleTag}</small>
                    </span>
                    {(gateProb !== null || conf !== null) && (
                      <span className={`d1-conf-mini is-${tone}`}>
                        {gateProb !== null && conf !== null && Math.abs(gateProb - conf) >= 0.005
                          ? `Pass ${pct(gateProb, 0)} · Conf ${pct(conf, 0)}`
                          : pct((gateProb ?? conf)!, 0)}
                      </span>
                    )}
                  </div>
                  <div className="d1-choice">{answerHeadline(answer)}</div>
                  <ProbabilityBars answer={answer} />
                </div>
              );
            })}
          </div>
        </details>
      )}
    </section>
  );
}
