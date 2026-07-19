import { DashboardShell } from "@/components/dashboard-shell";
import { OperantCommandCenter } from "@/components/operant-command-center";
import { CommandCenterAnalytics } from "@/components/command-center-analytics";
import { BusinessValueAnalytics } from "@/components/business-value-analytics";
import { loadUiCapabilityProjection } from "@/lib/server/load-ui-capability-projection.server";

/**
 * Command Center — Model A universal landing.
 *
 * Universal render (every authenticated tenant operator): static orientation panels only.
 * Analytics-backed panels are server-gated by projected VIEW_ANALYTICS and are not fetched
 * when the capability is absent or the projection is UNAVAILABLE/DENIED. The browser cannot
 * manufacture the capability. BFF/Core remain authoritative for every data request.
 */
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

function mayOfferAnalytics(capabilities: readonly string[], status: string): boolean {
  return status === "ALLOWED" && capabilities.includes("VIEW_ANALYTICS");
}

export default async function CommandCenterPage() {
  const [health, projection] = await Promise.all([loadHealth(), loadUiCapabilityProjection()]);
  const showAnalytics = mayOfferAnalytics(projection.capabilities, projection.status);

  return (
    <DashboardShell title="Command Center">
      <div className="page-grid">
        <section className="panel">
          <h2>Workspace</h2>
          <div className="status-row">
            <span className="status-dot" aria-hidden="true" />
            <p>Authenticated tenant operator landing. Destinations appear only when the server projects a matching UI capability.</p>
          </div>
        </section>
        <section className="panel">
          <h2>Core API reachability</h2>
          <div className="status-row">
            <span className="status-dot" aria-hidden="true" />
            <p>{health}</p>
          </div>
          <p className="risk-note">Health is a bounded reachability signal. It is not a security posture, billing, or integration readiness claim.</p>
        </section>
        <section className="panel">
          <h2>How to start</h2>
          <p>Use primary navigation or the command palette to open an offered work surface. Reload preserves server-owned session state. Logout ends access immediately.</p>
        </section>
        {showAnalytics ? (
          <>
            <OperantCommandCenter />
            <CommandCenterAnalytics />
            <BusinessValueAnalytics />
          </>
        ) : null}
      </div>
    </DashboardShell>
  );
}
