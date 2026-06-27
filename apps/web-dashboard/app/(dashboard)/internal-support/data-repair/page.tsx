import { Suspense } from "react";
import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  DataRepairOperationsView,
  DataRepairOperationsViewSkeleton
} from "@/components/data-repair-operations-view";

// OP-CAP-57 — single data-repair request operations view (read-only) for a SELECTED tenant.
//
// Both the tenant id and the request id are safe resource locators carried as query parameters; neither is
// authority. The backend re-validates the staff actor's active support grant for the selected tenant before
// returning anything. This page renders nothing mutating.

export default async function Page({
  searchParams
}: Readonly<{ searchParams: Promise<{ tenantId?: string; requestId?: string }> }>) {
  const { tenantId, requestId } = await searchParams;
  const selectedTenant = (tenantId ?? "").trim();
  const trimmedRequest = (requestId ?? "").trim();

  return (
    <DashboardShell title="Internal Support">
      <section className="panel">
        <div className="status-row">
          <h2>Data-Repair Request Operations</h2>
          <Link
            className="button table-link-button"
            href={
              selectedTenant
                ? `/internal-support/operations?tenantId=${encodeURIComponent(selectedTenant)}`
                : "/internal-support"
            }
          >
            Back to operations
          </Link>
        </div>
        <p>Read-only support diagnostics for a single data-repair request for the selected tenant.</p>
        {selectedTenant !== "" ? (
          <form className="control-grid" method="get">
            <input type="hidden" name="tenantId" value={selectedTenant} />
            <label className="field-label" htmlFor="requestId">
              Data-repair request id
            </label>
            <input
              id="requestId"
              name="requestId"
              className="form-input"
              type="text"
              defaultValue={trimmedRequest}
              placeholder="Data-repair request id"
              required
            />
            <button className="button" type="submit">
              Open operations view
            </button>
          </form>
        ) : null}
      </section>

      {selectedTenant === "" ? (
        <section className="panel">
          <p className="muted-copy">Select a tenant from the locator first to inspect its data-repair requests.</p>
        </section>
      ) : trimmedRequest === "" ? (
        <section className="panel">
          <p className="muted-copy">Enter a data-repair request id to inspect it.</p>
        </section>
      ) : (
        <Suspense fallback={<DataRepairOperationsViewSkeleton />}>
          <DataRepairOperationsView tenantId={selectedTenant} requestId={trimmedRequest} />
        </Suspense>
      )}
    </DashboardShell>
  );
}
