import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Processing Jobs">
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Job Type</th><th>Target</th><th>Status</th><th>Queued</th></tr></thead>
          <tbody><tr><td>Document / Message / Attachment</td><td>Inbound record</td><td>Queued</td><td>Timestamp</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}