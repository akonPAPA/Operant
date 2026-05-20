import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Analytics">
      <div className="page-grid">
        <section className="panel"><h2>Total sales amount</h2><p>0 until invoice or sales mirror records are added.</p></section>
        <section className="panel"><h2>Bot RFQ requests</h2><p>Updates after the demo Telegram RFQ is submitted to core-api.</p></section>
        <section className="panel"><h2>Reconciliation risk</h2><p>Open and high-severity counts summarize current tenant cases.</p></section>
        <section className="panel"><h2>Channel breakdown</h2><p>Telegram demo activity appears after the bot runtime receives RFQ and unknown messages.</p></section>
      </div>
    </DashboardShell>
  );
}
