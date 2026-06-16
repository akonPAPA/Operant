import { ConversionReviewDetail } from "@/components/conversion-review-cockpit";
import { DashboardShell } from "@/components/dashboard-shell";

export default async function Page({ params }: Readonly<{ params: Promise<{ attemptId: string }> }>) {
  const { attemptId } = await params;
  return (
    <DashboardShell title="Conversion Attempt Review">
      <ConversionReviewDetail attemptId={attemptId} />
    </DashboardShell>
  );
}
