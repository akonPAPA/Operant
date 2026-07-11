import { DashboardShell } from "@/components/dashboard-shell";
import { ValidationReviewDetailView } from "@/components/validation-review-detail";
import { ValidationReviewActionsClient } from "@/components/validation-review-actions";
import { ValidationReviewDraftControls } from "@/components/validation-review-draft-controls";
import { getValidationReviewByRun } from "@/lib/server/validation-review-detail-api.server";
import { getValidationReviewDraftStatus, getValidationReviewDraftability } from "@/lib/server/validation-review-draft-command-api.server";

// OP-CAP-14B / 15B / 15C — operator validation review workspace (read-only detail + narrow client controls).
// Route: /validations/{validationRunId}/review (nested under the existing /validations/[id] slug).
// Consumes OP-CAP-14A review detail + OP-CAP-15B draft status + OP-CAP-15C draftability hints (all reads).
// No business mutation here.
export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const review = await getValidationReviewByRun(id);
  // OP-CAP-15B/15C: server-rendered draft visibility + advisory line draftability passed to the controls.
  const draftStatus = review.data ? (await getValidationReviewDraftStatus(id)).data : null;
  const draftability = review.data ? (await getValidationReviewDraftability(id)).data : null;

  return (
    <DashboardShell title="Validation Review">
      {review.error ? (
        <section className="panel">
          <p className="form-message error">{review.error}</p>
          <p className="muted-copy">This screen is read-only. No validation, handoff, draft or business action was triggered.</p>
        </section>
      ) : null}
      {review.data ? (
        <>
          <ValidationReviewDetailView detail={review.data} />
          <ValidationReviewActionsClient detail={review.data} />
          <ValidationReviewDraftControls detail={review.data} initialDraftStatus={draftStatus} draftability={draftability} />
        </>
      ) : !review.error ? (
        <section className="panel">
          <p className="muted-copy">No validation review is available for run {id}.</p>
        </section>
      ) : null}
    </DashboardShell>
  );
}
