import { DashboardShell } from "@/components/dashboard-shell";
import { DraftReviewWorkspace } from "@/components/draft-review-workspace";
import { getDraftOrderReview } from "@/lib/draft-review-api";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const review = await getDraftOrderReview(id);

  return (
    <DashboardShell title="Draft Order Review">
      <DraftReviewWorkspace draftType="ORDER" initialDetail={review.data ?? null} initialError={review.error} />
    </DashboardShell>
  );
}
