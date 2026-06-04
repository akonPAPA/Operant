import { DashboardShell } from "@/components/dashboard-shell";
import { QuoteReviewDetailWorkspace } from "@/components/quote-review-cockpit";

export default function Page({ params, searchParams }: { params: { quoteId: string }; searchParams?: { tenantId?: string } }) {
  return (
    <DashboardShell title="Quote Review Detail">
      <QuoteReviewDetailWorkspace quoteId={params.quoteId} initialTenantId={searchParams?.tenantId} />
    </DashboardShell>
  );
}
