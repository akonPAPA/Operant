import { DashboardShell } from "@/components/dashboard-shell";
import { OperantCommandCenter } from "@/components/operant-command-center";
import { CommandCenterAnalytics } from "@/components/command-center-analytics";
import { BusinessValueAnalytics } from "@/components/business-value-analytics";

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
          <p>Frontend shell has no database path. Analytics panels read tenant-scoped core-api aggregates only.</p>
        </section>
        <section className="panel">
          <h2>Investor demo path</h2>
          <p>Use the Investor Demo route to show Telegram RFQ intake, human handoff, reconciliation, analytics, and trust controls in one guided flow.</p>
        </section>
        <OperantCommandCenter />
        <CommandCenterAnalytics />
        <BusinessValueAnalytics />
        <section className="panel">
          <h2>Bot runtime</h2>
          <p>Telegram-style updates are accepted by core-api for demo intake. No real Telegram API calls or business replies are sent.</p>
        </section>
      </div>
    </DashboardShell>
  );
}
