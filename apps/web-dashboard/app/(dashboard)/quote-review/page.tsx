import { DashboardShell } from "@/components/dashboard-shell";
import { QuoteReviewQueue } from "@/components/quote-review-cockpit";

export default function Page() {
  return (
    <DashboardShell title="Quote Review Cockpit">
      <QuoteReviewQueue />
    </DashboardShell>
  );
}
