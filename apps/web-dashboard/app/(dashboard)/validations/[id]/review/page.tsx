import { DashboardShell } from "@/components/dashboard-shell";
import { ValidationReviewDetailView } from "@/components/validation-review-detail";
import { ValidationReviewActionsClient } from "@/components/validation-review-actions";
import { ValidationReviewDraftControls } from "@/components/validation-review-draft-controls";
import { getValidationReviewByRun } from "@/lib/validation-review-detail-api";
import { getValidationReviewDraftStatus } from "@/lib/validation-review-draft-command-api";

// OP-CAP-14B / 15B — operator validation review workspace (read-only detail + narrow client controls).
// Route: /validations/{validationRunId}/review (nested under the existing /validations/[id] slug).
// Consumes OP-CAP-14A review detail + OP-CAP-15B draft status (both reads). No business mutation here.
export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const review = await getValidationReviewByRun(id);
  // OP-CAP-15B: server-rendered draft visibility passed into the narrow client controls.
  const draftStatus = review.data ? (await getValidationReviewDraftStatus(id)).data : null;

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
          <ValidationReviewDraftControls detail={review.data} initialDraftStatus={draftStatus} />
        </>
      ) : !review.error ? (
        <section className="panel">
          <p className="muted-copy">No validation review is available for run {id}.</p>
        </section>
      ) : null}
    </DashboardShell>
  );
}
