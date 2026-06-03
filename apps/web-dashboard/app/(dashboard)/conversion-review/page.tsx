import { ConversionReviewList } from "@/components/conversion-review-cockpit";
import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Conversion Review">
      <ConversionReviewList />
    </DashboardShell>
  );
}
