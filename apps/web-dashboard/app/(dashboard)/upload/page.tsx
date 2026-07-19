import { DashboardShell } from "@/components/dashboard-shell";
import { IntakeUploadForm } from "@/components/intake-upload-form";
import { UnavailableState } from "@/components/page-states";
import { isUploadAvailable, uploadCapability, uploadUnavailableMessage } from "@/lib/upload-capability";

export default function Page() {
  const capability = uploadCapability();
  if (!isUploadAvailable(capability)) {
    return (
      <DashboardShell title="Document Upload">
        <UnavailableState
          title="Upload not available"
          description={uploadUnavailableMessage()}
          reason="Manual upload is disabled for this deployment profile. Intake continues through supported channel paths when configured."
        />
      </DashboardShell>
    );
  }
  return (
    <DashboardShell title="Document Upload">
      <section className="panel">
        <h2>Manual file intake</h2>
        <p>Allowed: PDF, CSV, XLS, XLSX, PNG, JPEG, and plain text. Default max size: 10 MB. Files are stored as untrusted objects and queued for future processing.</p>
      </section>
      <section className="panel">
        <h2>Upload result</h2>
        <p>Local demo uploads send only business input; no direct database or object-store access is allowed from the frontend.</p>
      </section>
      <IntakeUploadForm />
    </DashboardShell>
  );
}
