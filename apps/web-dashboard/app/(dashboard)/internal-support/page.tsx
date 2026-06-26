import { Suspense } from "react";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  SupportOperationsSummaryPanel,
  SupportOperationsSummarySkeleton,
  SupportOperationsTimelinePanel,
  SupportOperationsTimelineSkeleton
} from "@/components/support-operations-overview";

// OP-CAP-56 — Internal Support Operations Visibility (read-only).
//
// Owner-company support operators land here to inspect one tenant's operations state via the OP-CAP-55
// read-only endpoints. The target tenant is the trusted server-resolved demo scope (X-Tenant-Id /
// NEXT_PUBLIC_DEMO_TENANT_ID) — there is no editable tenant field on this surface. The only operator input
// is the safe bounded page/size pagination and a request-id locator to open a single data-repair view.

const DEFAULT_PAGE_SIZE = 20;

function clampPage(raw: string | undefined): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function clampSize(raw: string | undefined): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  if (!Number.isFinite(parsed) || parsed <= 0) return DEFAULT_PAGE_SIZE;
  return Math.min(parsed, 100);
}

export default async function Page({
  searchParams
}: Readonly<{ searchParams: Promise<{ page?: string; size?: string }> }>) {
  const { page, size } = await searchParams;
  const pageNumber = clampPage(page);
  const pageSize = clampSize(size);

  return (
    <DashboardShell title="Internal Support">
      <section className="panel">
        <h2>Internal Support Operations</h2>
        <p>
          Read-only operational visibility for owner-company support diagnostics. Inspect a tenant&apos;s
          incident, break-glass, support-grant, and data-repair lifecycle state. This surface performs no
          mutation, impersonation, or execution.
        </p>
        <p className="risk-note">
          Requires staff support permission and an active support grant. Tenant scope is fixed to the
          configured support context — it cannot be edited here.
        </p>
        <form className="control-grid" method="get" action="/internal-support/data-repair">
          <label className="field-label" htmlFor="requestId">
            Open a data-repair request operations view
          </label>
          <input
            id="requestId"
            name="requestId"
            className="form-input"
            type="text"
            placeholder="Data-repair request id"
            required
          />
          <button className="button" type="submit">
            Open operations view
          </button>
        </form>
      </section>

      <Suspense fallback={<SupportOperationsSummarySkeleton />}>
        <SupportOperationsSummaryPanel />
      </Suspense>

      <Suspense fallback={<SupportOperationsTimelineSkeleton />}>
        <SupportOperationsTimelinePanel page={pageNumber} size={pageSize} />
      </Suspense>
    </DashboardShell>
  );
}
