import Link from "next/link";

import { searchSupportTenants } from "@/lib/internal-support-operations-api";
import type { SupportTenantLocatorResult } from "@/lib/internal-support-operations-api";

// OP-CAP-57 — internal support tenant locator (read-only).
//
// Lets an Operant staff operator discover and select ONLY the tenants they may support. It calls the safe
// cross-tenant locator endpoint (filtered server-side to tenants the staff actor holds an active diagnostics
// grant for) and renders a bounded result list. A safe display name/slug is the primary identity; the raw
// tenant id is used only as the navigation handle on the "Open operations" link. Strictly display-only: it
// submits nothing and renders no authority field, no staff/actor input, and no mutation/repair/break-glass
// control. The selected tenant id is navigation context, never authority — the backend re-validates the
// grant on every downstream support call.

const DEFAULT_PAGE_SIZE = 20;

export async function SupportTenantLocator({
  query,
  page,
  size
}: Readonly<{ query: string; page: number; size: number }>) {
  const { data, error } = await searchSupportTenants(query, { page, size });

  return (
    <section className="panel table-panel">
      <div className="status-row">
        <h2>Supportable tenants</h2>
        {data ? (
          <span className="muted-copy">
            Page {data.page + 1} · {data.returnedCount} tenant{data.returnedCount === 1 ? "" : "s"}
          </span>
        ) : null}
      </div>

      {!data ? (
        <p className="muted-copy">{error ?? "Tenant locator is unavailable right now. Please retry shortly."}</p>
      ) : data.results.length === 0 ? (
        <p className="muted-copy">
          No tenants match your access. You can only locate tenants you hold an active support grant for.
        </p>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>Tenant</th>
              <th>Handle</th>
              <th>Status</th>
              <th>Support scopes</th>
              <th>Grant expires</th>
              <th>Open</th>
            </tr>
          </thead>
          <tbody>
            {data.results.map((tenant) => (
              <TenantRow key={tenant.tenantId} tenant={tenant} />
            ))}
          </tbody>
        </table>
      )}

      {data ? <LocatorPager query={query} page={data.page} size={data.pageSize} hasMore={data.hasMore} /> : null}
    </section>
  );
}

export function SupportTenantLocatorSkeleton() {
  return (
    <section className="panel" aria-busy="true" aria-label="Loading tenant locator">
      <h2>Supportable tenants</h2>
      <p className="muted-copy">Loading supportable tenants…</p>
    </section>
  );
}

function TenantRow({ tenant }: Readonly<{ tenant: SupportTenantLocatorResult }>) {
  return (
    <tr>
      <td>
        <strong>{tenant.displayName}</strong>
      </td>
      <td>{tenant.slug}</td>
      <td>
        <span className="status-pill" data-state={tenant.status}>
          {tenant.status}
        </span>
      </td>
      <td>{tenant.supportScopes.length > 0 ? tenant.supportScopes.join(", ") : "—"}</td>
      <td>{formatTimestamp(tenant.grantExpiresAt)}</td>
      <td>
        {/* tenantId is the navigation handle only; the backend re-validates the grant on the next call. */}
        <Link
          className="button table-link-button"
          href={`/internal-support/operations?tenantId=${encodeURIComponent(tenant.tenantId)}`}
        >
          Open operations
        </Link>
      </td>
    </tr>
  );
}

function LocatorPager({
  query,
  page,
  size,
  hasMore
}: Readonly<{ query: string; page: number; size: number; hasMore: boolean }>) {
  const base = (target: number) => {
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    params.set("page", String(target));
    params.set("size", String(size || DEFAULT_PAGE_SIZE));
    return `/internal-support?${params.toString()}`;
  };
  return (
    <div className="status-row" aria-label="Tenant locator pagination">
      {page > 0 ? (
        <Link className="button table-link-button" href={base(page - 1)}>
          Previous
        </Link>
      ) : (
        <span className="muted-copy">Previous</span>
      )}
      {hasMore ? (
        <Link className="button table-link-button" href={base(page + 1)}>
          Next
        </Link>
      ) : (
        <span className="muted-copy">Next</span>
      )}
    </div>
  );
}

function formatTimestamp(value: string | null | undefined): string {
  if (typeof value !== "string" || value.trim() === "") {
    return "—";
  }
  return value;
}
