import { DashboardShell } from "@/components/dashboard-shell";
import { ValidationReviewDetailView } from "@/components/validation-review-detail";
import { getValidationReviewByRun } from "@/lib/validation-review-detail-api";

// OP-CAP-14B — read-only operator validation review workspace.
// Route: /validations/{validationRunId}/review (nested under the existing /validations/[id] slug).
// Consumes OP-CAP-14A GET /api/v1/validations/{validationRunId}/review. No business mutation.
export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const review = await getValidationReviewByRun(id);

  return (
    <DashboardShell title="Validation Review">
      {review.error ? (
        <section className="panel">
          <p className="form-message error">{review.error}</p>
          <p className="muted-copy">This screen is read-only. No validation, handoff, draft or business action was triggered.</p>
        </section>
      ) : null}
      {review.data ? (
        <ValidationReviewDetailView detail={review.data} />
      ) : !review.error ? (
        <section className="panel">
          <p className="muted-copy">No validation review is available for run {id}.</p>
        </section>
      ) : null}
    </DashboardShell>
  );
}
