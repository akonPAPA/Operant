import { ConversionReviewDetail } from "@/components/conversion-review-cockpit";
import { DashboardShell } from "@/components/dashboard-shell";

export default async function Page({ params, searchParams }: Readonly<{ params: Promise<{ attemptId: string }>; searchParams: Promise<{ tenantId?: string }> }>) {
  const [{ attemptId }, query] = await Promise.all([params, searchParams]);
  return (
    <DashboardShell title="Conversion Attempt Review">
      <ConversionReviewDetail attemptId={attemptId} initialTenantId={query.tenantId} />
    </DashboardShell>
  );
}
