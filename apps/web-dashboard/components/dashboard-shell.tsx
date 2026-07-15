import Link from "next/link";
import { productBrand } from "@/lib/brand";
import { navigationGroupsForUploadCapability } from "./navigation";

export function DashboardShell({
  title,
  children,
  inspector
}: Readonly<{ title: string; children: React.ReactNode; inspector?: React.ReactNode }>) {
  const navigationGroups = navigationGroupsForUploadCapability();

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">O</div>
          <div>
            <strong>{productBrand.name}</strong>
            <span>{productBrand.descriptor}</span>
          </div>
        </div>
        <nav className="nav" aria-label="Primary navigation">
          {navigationGroups.map((group) => (
            <details className="nav-group" key={group.label} open={group.label === title || group.items.some((item) => item.label === title)}>
              <summary>
                <span className="nav-code" aria-hidden="true">{group.code}</span>
                <span>{group.label}</span>
              </summary>
              <div className="nav-children">
                {group.items.map((item) => (
                  <Link href={item.href} key={`${group.label}-${item.href}-${item.label}`}>
                    <span>{item.label}</span>
                    {item.badge ? <span className="nav-badge">{item.badge}</span> : null}
                  </Link>
                ))}
              </div>
            </details>
          ))}
        </nav>
      </aside>
      <main className="main">
        <header className="topbar">
          <div className="topbar-title">
            <span className="eyebrow">Operator Workspace</span>
            <h1>{title}</h1>
          </div>
          <div className="topbar-actions" aria-label="Workspace status">
            <span className="tenant-pill">Tenant scoped</span>
            <span className="tenant-pill system-ok">External execution disabled</span>
          </div>
        </header>
        <section className={inspector ? "content content-with-inspector" : "content"}>
          <div className="content-main">{children}</div>
          {inspector ? <aside className="right-inspector">{inspector}</aside> : null}
        </section>
      </main>
    </div>
  );
}
