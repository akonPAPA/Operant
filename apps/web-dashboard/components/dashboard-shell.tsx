import Link from "next/link";
import { navigationItems } from "./navigation";

export function DashboardShell({ title, children }: Readonly<{ title: string; children: React.ReactNode }>) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <strong>OrderPilot</strong>
          <span>Secure transaction intelligence for B2B distributors</span>
        </div>
        <nav className="nav" aria-label="Primary navigation">
          {navigationItems.map((item) => (
            <Link href={item.href} key={item.href}>
              {item.label}
            </Link>
          ))}
        </nav>
      </aside>
      <main className="main">
        <header className="topbar">
          <h1>{title}</h1>
          <span className="tenant-pill">Tenant boundary placeholder</span>
        </header>
        <section className="content">{children}</section>
      </main>
    </div>
  );
}