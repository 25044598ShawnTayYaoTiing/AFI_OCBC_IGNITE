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

export function minimumDecisionConfidence(decision?: DecisionAssessment | null): number | null {
  if (!decision || decision.outcome === "UNAVAILABLE") return null;
  if (typeof decision.min_confidence === "number" && Number.isFinite(decision.min_confidence)) {
    return decision.min_confidence;
  }
  const values = Object.values(decision.answers ?? {})
    .map((ans) => answerConfidence(ans))
    .filter((value): value is number => value !== null);
  return values.length ? Math.min(...values) : null;
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

export function DecisionAssessmentPanel({ decision }: { decision: DecisionAssessment }) {
  const threshold = decision.threshold ?? 0.8;
  const minConf = minimumDecisionConfidence(decision);
  const band = minConf !== null ? confidenceTone(minConf, threshold) : null;
  const entries = Object.entries(decision.answers ?? {});

  return (
    <section className="d1-panel" aria-label="Decision 1 assessment">
      <div className="d1-header">
        <div>
          <strong className="d1-title">
            {decision.outcome === "UNAVAILABLE"
              ? "AI evidence assessment unavailable"
              : decision.outcome === "PASSED"
                ? "Decision 1 verified"
                : "Decision 1 flagged for review"}
          </strong>
          <span className="d1-meta">
            {decision.model ?? "gemini-decision-1"} · Threshold {pct(threshold)}
            {typeof decision.elapsed_ms === "number" ? ` · ${(decision.elapsed_ms / 1000).toFixed(2)}s` : ""}
            {typeof decision.usage?.total_input_tokens === "number"
              ? ` · ${decision.usage.total_input_tokens.toLocaleString()} tok`
              : ""}
          </span>
        </div>
        {minConf !== null && band && (
          <span className={`d1-conf-pill is-${band}`}>
            Min confidence {pct(minConf, 1)}
          </span>
        )}
      </div>

      {entries.length > 0 && (
        <details className="d1-details" open={decision.outcome !== "PASSED"}>
          <summary>
            Probability breakdown ({entries.length} checks)
          </summary>
          <div className="d1-grid">
            {entries.map(([key, answer]) => {
              const conf = answerConfidence(answer);
              const tone = conf !== null ? confidenceTone(conf, threshold) : "low";
              return (
                <div key={key} className="d1-card">
                  <div className="d1-card-top">
                    <span className="d1-q">{QUESTION_LABELS[key] ?? formatOption(key)}</span>
                    {conf !== null && (
                      <span className={`d1-conf-mini is-${tone}`}>{pct(conf, 0)}</span>
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
