import { DashboardShell } from "@/components/dashboard-shell";
import { DraftReviewWorkspace } from "@/components/draft-review-workspace";
import { getDraftQuoteReview } from "@/lib/draft-review-api";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const review = await getDraftQuoteReview(id);

  return (
    <DashboardShell title="Draft Quote Review">
      <DraftReviewWorkspace draftType="QUOTE" initialDetail={review.data ?? null} initialError={review.error} />
    </DashboardShell>
  );
}
