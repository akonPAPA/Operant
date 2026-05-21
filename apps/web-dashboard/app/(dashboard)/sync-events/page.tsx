import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Sync Events">
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Provider</th><th>Sync type</th><th>Direction</th><th>Counts</th><th>Status</th><th>Duration</th><th>Error category</th><th>Timestamps</th></tr></thead>
          <tbody>
            <tr><td>Demo ERP / CSV / Excel</td><td>PRODUCT_IMPORT</td><td>INBOUND</td><td>Read N / Written 0 / Failed 0</td><td>SUCCESS / FAILED</td><td>durationMs</td><td>Normalized if failed</td><td>Started and finished by API</td></tr>
          </tbody>
        </table>
        <p className="risk-note">Stage 13 sync actions record audit-friendly read-only history through core-api. Production external writes remain disabled.</p>
      </section>
    </DashboardShell>
  );
}
