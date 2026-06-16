import { DashboardShell } from "@/components/dashboard-shell";

const settingsGroups = [
  {
    title: "Organization",
    description: "Tenant profile, operating units, locations, and business calendar defaults."
  },
  {
    title: "Users & Access",
    description: "Operator roles, permission groups, review responsibility, and access governance."
  },
  {
    title: "Security",
    description: "Authentication posture, tenant isolation checks, audit access, and webhook safety controls."
  },
  {
    title: "Channels",
    description: "Inbound document, email, API, and messenger channel connection status."
  },
  {
    title: "Integrations",
    description: "Connector registrations, ChangeRequest boundaries, sync visibility, and external execution policy."
  },
  {
    title: "AI & Runtime",
    description: "Advisory extraction, bot runtime policy, deterministic validation gates, and worker status."
  },
  {
    title: "Advanced",
    description: "Operational diagnostics, import controls, retention settings, and support-only configuration."
  }
];

export default function Page() {
  return (
    <DashboardShell title="Settings">
      <section className="panel settings-hero">
        <div>
          <span className="eyebrow">Settings Hub</span>
          <h2>Controlled configuration workspace</h2>
          <p>Settings are grouped by operational authority. This page is a dashboard shell only; business mutations still require backend command services, tenant checks, permissions, validation, and audit.</p>
        </div>
        <span className="status-pill warning">No direct database writes</span>
      </section>
      <section className="settings-layout">
        <nav className="settings-subnav" aria-label="Settings groups">
          {settingsGroups.map((group) => (
            <a href={`#${settingId(group.title)}`} key={group.title}>{group.title}</a>
          ))}
        </nav>
        <div className="settings-sections">
          {settingsGroups.map((group, index) => (
            <details className="settings-accordion" id={settingId(group.title)} key={group.title} open={index < 2}>
              <summary>
                <span>{group.title}</span>
                <span className="status-pill">Planned controls</span>
              </summary>
              <p>{group.description}</p>
              <p className="muted-copy">Configuration commands are intentionally not wired from this shell until the corresponding backend authorization and audit contract exists.</p>
            </details>
          ))}
        </div>
      </section>
    </DashboardShell>
  );
}

function settingId(value: string) {
  return value.toLowerCase().replaceAll(" & ", "-").replaceAll(" ", "-");
}
