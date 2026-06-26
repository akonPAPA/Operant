import { Suspense } from "react";
import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  DataRepairOperationsView,
  DataRepairOperationsViewSkeleton
} from "@/components/data-repair-operations-view";

// OP-CAP-56 — single data-repair request operations view (read-only).
//
// The request id is a safe resource locator carried as a route/query parameter; the tenant scope stays the
// trusted server-resolved support context (X-Tenant-Id). This page renders nothing mutating.

export default async function Page({
  searchParams
}: Readonly<{ searchParams: Promise<{ requestId?: string }> }>) {
  const { requestId } = await searchParams;
  const trimmed = (requestId ?? "").trim();

  return (
    <DashboardShell title="Internal Support">
      <section className="panel">
        <div className="status-row">
          <h2>Data-Repair Request Operations</h2>
          <Link className="button table-link-button" href="/internal-support">
            Back to operations
          </Link>
        </div>
        <p>Read-only support diagnostics for a single data-repair request in the configured support context.</p>
      </section>

      {trimmed === "" ? (
        <section className="panel">
          <p className="muted-copy">Enter a data-repair request id from the operations overview to inspect it.</p>
        </section>
      ) : (
        <Suspense fallback={<DataRepairOperationsViewSkeleton />}>
          <DataRepairOperationsView requestId={trimmed} />
        </Suspense>
      )}
    </DashboardShell>
  );
}
