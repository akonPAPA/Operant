import { Suspense } from "react";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  SupportTenantLocator,
  SupportTenantLocatorSkeleton
} from "@/components/support-tenant-locator";

// OP-CAP-57 — Internal Support landing: TENANT LOCATOR (read-only).
//
// Replaces the OP-CAP-56 demo-tenant assumption. An Operant staff operator searches for a tenant they are
// allowed to support (results are filtered server-side to tenants the staff actor holds an active support
// grant for) and opens read-only operations visibility for the selected tenant. The selected tenant id is
// carried only as a navigation handle — the backend re-resolves the staff actor and re-validates the grant
// on every downstream support call. There is no editable tenant/actor/authority field on this surface.

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
}: Readonly<{ searchParams: Promise<{ q?: string; page?: string; size?: string }> }>) {
  const { q, page, size } = await searchParams;
  const query = (q ?? "").trim();
  const pageNumber = clampPage(page);
  const pageSize = clampSize(size);

  return (
    <DashboardShell title="Internal Support">
      <section className="panel">
        <h2>Internal Support — Tenant Locator</h2>
        <p>
          Locate a tenant you are authorized to support and open read-only operational visibility. You can
          only find tenants you hold an active, approved support grant for; access is re-checked by the
          backend on every view.
        </p>
        <p className="risk-note">
          Read-only. No tenant data mutation, impersonation, repair execution, or break-glass action is
          available here. Tenant scope is enforced by your support grant, not by this page.
        </p>
        <form className="control-grid" method="get">
          <label className="field-label" htmlFor="q">
            Search tenants by name or handle
          </label>
          <input
            id="q"
            name="q"
            className="form-input"
            type="text"
            defaultValue={query}
            placeholder="e.g. Acme"
          />
          <button className="button" type="submit">
            Search
          </button>
        </form>
      </section>

      <Suspense fallback={<SupportTenantLocatorSkeleton />}>
        <SupportTenantLocator query={query} page={pageNumber} size={pageSize} />
      </Suspense>
    </DashboardShell>
  );
}
