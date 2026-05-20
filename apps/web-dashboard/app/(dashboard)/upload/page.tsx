import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Document Upload">
      <section className="panel">
        <h2>Manual file intake</h2>
        <p>Allowed: PDF, CSV, XLS, XLSX, PNG, JPEG, and plain text. Default max size: 10 MB. Files are stored as untrusted objects and queued for future processing.</p>
      </section>
      <section className="panel">
        <h2>Upload result</h2>
        <p>The browser must call `POST /api/v1/intake/documents/upload`; no direct database or object-store access is allowed from the frontend.</p>
      </section>
    </DashboardShell>
  );
}