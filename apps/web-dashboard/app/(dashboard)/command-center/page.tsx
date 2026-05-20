import { DashboardShell } from "@/components/dashboard-shell";

async function loadHealth(): Promise<string> {
  const baseUrl = process.env.CORE_API_BASE_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(`${baseUrl}/api/v1/health`, { cache: "no-store" });
    if (!response.ok) {
      return "Core API unavailable";
    }
    const data = (await response.json()) as { status?: string };
    return data.status ?? "Unknown";
  } catch {
    return "Core API not reachable";
  }
}

export default async function CommandCenterPage() {
  const health = await loadHealth();

  return (
    <DashboardShell title="Command Center">
      <div className="page-grid">
        <section className="panel">
          <h2>Core API</h2>
          <div className="status-row">
            <span className="status-dot" aria-hidden="true" />
            <p>{health}</p>
          </div>
        </section>
        <section className="panel">
          <h2>Security posture</h2>
          <p>Frontend shell has no database path. Future actions route through core-api command services.</p>
        </section>
        <section className="panel">
          <h2>Investor demo path</h2>
          <p>Use the Investor Demo route to show Telegram RFQ intake, human handoff, reconciliation, analytics, and trust controls in one guided flow.</p>
        </section>
        <section className="panel">
          <h2>Bot runtime</h2>
          <p>Telegram-style updates are accepted by core-api for demo intake. No real Telegram API calls or business replies are sent.</p>
        </section>
        <section className="panel"><h2>Open exception cases</h2><p>Operator cases grouped from validation issues for review and resolution.</p></section>
        <section className="panel"><h2>Waiting approvals</h2><p>Internal workflow approvals for risky validation, quote, order, or substitute decisions.</p></section>
        <section className="panel"><h2>Draft quotes needing review</h2><p>Internal draft quotes awaiting operator cleanup. No customer sending.</p></section>
        <section className="panel"><h2>Draft orders needing review</h2><p>Internal draft orders awaiting review. No ERP or warehouse write.</p></section>
        <section className="panel"><h2>High severity issues</h2><p>Critical and error validation issues that need human attention first.</p></section>
      </div>
    </DashboardShell>
  );
}
