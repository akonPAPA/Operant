import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Integrations">
      <div className="page-grid">
        <section className="panel"><h2>Telegram webhook</h2><p>Demo button posts Telegram-shaped payloads to core-api. It does not call Telegram or send replies.</p></section>
        <section className="panel"><h2>Email and document intake</h2><p>Provider-neutral intake remains available for PDF and Excel-style workflows without external sending.</p></section>
        <section className="panel"><h2>ERP boundary</h2><p>No ERP, 1C, accounting, warehouse, or payment write integration is implemented in Stage 9C.</p></section>
        <section className="panel"><h2>Backend safety</h2><p>All demo calls route through tenant-scoped core-api services and audit-aware command paths.</p></section>
      </div>
    </DashboardShell>
  );
}
