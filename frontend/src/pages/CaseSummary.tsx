import { CircleAlert, CheckCircle2, ArrowRight } from "lucide-react";
import type { Detail } from "../document-types";
import { reviewState } from "./review-state";

export function CaseSummary({
  detail,
  onReview,
  onReadiness,
}: {
  detail: Detail;
  onReview: () => void;
  onReadiness: () => void;
}) {
  const state = reviewState(detail);
  return (
    <section
      className={`review-outcome ${state.routed || state.eligible ? "positive" : "attention"}`}
      aria-label="Case outcome"
    >
      {state.routed || state.eligible ? (
        <CheckCircle2 size={23} />
      ) : (
        <CircleAlert size={23} />
      )}
      <div>
        <h2>{state.title}</h2>
        <p>{state.next}</p>
        <button
          className="text-link"
          onClick={state.routed || state.eligible ? onReadiness : onReview}
        >
          {state.routed
            ? "View handover"
            : state.eligible
              ? "Inspect readiness"
              : "Review next steps"}
          <ArrowRight size={15} />
        </button>
      </div>
    </section>
  );
}
