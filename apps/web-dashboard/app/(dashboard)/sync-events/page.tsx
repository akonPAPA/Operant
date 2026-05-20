import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Sync Events">
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Provider</th><th>Sync type</th><th>Direction</th><th>Counts</th><th>Status</th><th>Timestamps</th></tr></thead>
          <tbody>
            <tr><td>1C / ERP / CSV</td><td>PRODUCT_IMPORT</td><td>INBOUND</td><td>Read 0 / Written 0 / Failed 0</td><td>SUCCESS</td><td>Started and finished by API</td></tr>
          </tbody>
        </table>
        <p className="risk-note">Stage 12 sync actions record audit-friendly history through core-api. Production external writes remain disabled.</p>
      </section>
    </DashboardShell>
  );
}
