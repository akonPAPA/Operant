import { Suspense } from "react";
import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  SupportTenantContextPanel,
  SupportOperationsSummaryPanel,
  SupportOperationsSummarySkeleton,
  SupportOperationsTimelinePanel,
  SupportOperationsTimelineSkeleton
} from "@/components/support-operations-overview";

// OP-CAP-57 — read-only operations visibility for a SELECTED tenant.
//
// The tenant id arrives as a navigation handle from the locator (?tenantId=...). It is not authority: the
// support-context panel re-confirms the staff actor's active grant, and every operations read is
// re-validated by the backend. Only the safe bounded page/size pagination and the tenant handle are sent.

const DEFAULT_PAGE_SIZE = 20;

function clampPage(raw: string | undefined): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function clampSize(raw: string | undefined): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  if (!Number.isFinite(parsed) || parsed <= 0) return DEFAULT_PAGE_SIZE;
  return Math.min(parsed, 50);
}

export default async function Page({
  searchParams
}: Readonly<{ searchParams: Promise<{ tenantId?: string; page?: string; size?: string }> }>) {
  const { tenantId, page, size } = await searchParams;
  const selectedTenant = (tenantId ?? "").trim();
  const pageNumber = clampPage(page);
  const pageSize = clampSize(size);

  return (
    <DashboardShell title="Internal Support">
      <section className="panel">
        <div className="status-row">
          <h2>Tenant Operations Visibility</h2>
          <Link className="button table-link-button" href="/internal-support">
            Back to locator
          </Link>
        </div>
        <p>Read-only support diagnostics for the selected tenant in the configured support context.</p>
      </section>

      {selectedTenant === "" ? (
        <section className="panel">
          <p className="muted-copy">Select a tenant from the locator to open its operations visibility.</p>
        </section>
      ) : (
        <>
          <Suspense fallback={<SupportOperationsSummarySkeleton />}>
            <SupportTenantContextPanel tenantId={selectedTenant} />
          </Suspense>
          <Suspense fallback={<SupportOperationsSummarySkeleton />}>
            <SupportOperationsSummaryPanel tenantId={selectedTenant} />
          </Suspense>
          <Suspense fallback={<SupportOperationsTimelineSkeleton />}>
            <SupportOperationsTimelinePanel tenantId={selectedTenant} page={pageNumber} size={pageSize} />
          </Suspense>
          <section className="panel">
            <p className="muted-copy">
              Need a specific data-repair request?{" "}
              <Link href={`/internal-support/data-repair?tenantId=${encodeURIComponent(selectedTenant)}`}>
                Open the data-repair operations view
              </Link>
              .
            </p>
          </section>
        </>
      )}
    </DashboardShell>
  );
}
