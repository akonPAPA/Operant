import { DashboardShell } from "@/components/dashboard-shell";
import { IntakeUploadForm } from "@/components/intake-upload-form";

export default function Page() {
  return (
    <DashboardShell title="Document Upload">
      <section className="panel">
        <h2>Manual file intake</h2>
        <p>Allowed: PDF, CSV, XLS, XLSX, PNG, JPEG, and plain text. Default max size: 10 MB. Files are stored as untrusted objects and queued for future processing.</p>
      </section>
      <section className="panel">
        <h2>Upload result</h2>
        <p>The browser calls the core-api upload endpoint only; no direct database or object-store access is allowed from the frontend.</p>
      </section>
      <IntakeUploadForm />
    </DashboardShell>
  );
}
