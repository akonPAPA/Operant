import { DashboardShell } from "@/components/dashboard-shell";

export default function AuditPage() {
  return (
    <DashboardShell title="Audit">
      <div className="page-grid">
        <section className="panel">
          <h2>Audit Events</h2>
          <p>Important backend actions must create tenant-scoped audit events.</p>
        </section>
        <section className="panel">
          <h2>Trust Boundary</h2>
          <p>The dashboard has no direct database write path. Business mutations go through core-api command services.</p>
        </section>
        <section className="panel">
          <h2>Current Scope</h2>
          <p>This page is a foundation route for audit visibility. Later-stage audit views may expose richer event history.</p>
        </section>
      </div>
    </DashboardShell>
  );
}
