import { DashboardShell } from "@/components/dashboard-shell";
import { ValidationReviewWorkspace } from "@/components/validation-review-workspace";
import { getDraftPreview, getValidationReviewCase, getValidationRunChecks } from "@/lib/validation-review-api";

export default async function Page({ params }: Readonly<{ params: Promise<{ reviewCaseId: string }> }>) {
  const { reviewCaseId } = await params;
  const reviewCase = await getValidationReviewCase(reviewCaseId);

  if (reviewCase.error || !reviewCase.data) {
    return (
      <DashboardShell title="Validation Review Detail">
        <section className="panel">
          <h2>Review case unavailable</h2>
          <p className="form-message error">{reviewCase.error ?? "Review case could not be loaded."}</p>
        </section>
      </DashboardShell>
    );
  }

  const checks = await getValidationRunChecks(reviewCase.data.validation.id);
  const preview = await getDraftPreview(reviewCase.data.reviewCase.id);

  return (
    <DashboardShell title="Validation Review Detail">
      {checks.error ? <section className="panel"><p className="form-message error">{checks.error}</p></section> : null}
      {preview.error ? <section className="panel"><p className="form-message error">{preview.error}</p></section> : null}
      <ValidationReviewWorkspace initialCase={reviewCase.data} checks={checks.data} initialPreview={preview.data} />
    </DashboardShell>
  );
}
