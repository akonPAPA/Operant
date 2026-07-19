import Link from "next/link";
import { productBrand } from "@/lib/brand";
import { navigationGroupsForUploadCapability } from "./navigation";
import { breadcrumbTrailForTitle, paletteDestinations } from "./navigation-registry.ts";
import { CommandPalette, type PaletteEntry } from "./command-palette.tsx";

export function DashboardShell({
  title,
  children,
  inspector
}: Readonly<{ title: string; children: React.ReactNode; inspector?: React.ReactNode }>) {
  const navigationGroups = navigationGroupsForUploadCapability();
  const breadcrumbs = breadcrumbTrailForTitle(title);
  // Tenant-plane, palette-visible destinations resolved on the server; passed to the client palette
  // as plain data. UI visibility is not authorization — Core still authorizes every route.
  const paletteEntries: PaletteEntry[] = paletteDestinations("TENANT").map((dest) => ({
    id: dest.id,
    path: dest.path,
    label: dest.label,
    section: dest.section,
    searchAliases: dest.searchAliases
  }));

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
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
      <main className="main" id="main-content" tabIndex={-1}>
        <header className="topbar">
          <div className="topbar-title">
            {breadcrumbs ? (
              <nav className="breadcrumbs" aria-label="Breadcrumb">
                <ol>
                  {breadcrumbs.map((crumb, index) => (
                    <li key={`${crumb.label}-${index}`} aria-current={index === breadcrumbs.length - 1 ? "page" : undefined}>
                      {crumb.label}
                    </li>
                  ))}
                </ol>
              </nav>
            ) : (
              <span className="eyebrow">Operator Workspace</span>
            )}
            <h1>{title}</h1>
          </div>
          <div className="topbar-actions" aria-label="Workspace status">
            <CommandPalette entries={paletteEntries} />
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
