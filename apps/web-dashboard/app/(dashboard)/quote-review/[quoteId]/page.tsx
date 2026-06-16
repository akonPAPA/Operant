import { DashboardShell } from "@/components/dashboard-shell";
import { QuoteReviewDetailWorkspace } from "@/components/quote-review-cockpit";

export default function Page({ params }: { params: { quoteId: string } }) {
  return (
    <DashboardShell title="Quote Review Detail">
      <QuoteReviewDetailWorkspace quoteId={params.quoteId} />
    </DashboardShell>
  );
}
