import { DashboardShell } from "@/components/dashboard-shell";
import { IntakeUploadForm } from "@/components/intake-upload-form";
import { UnavailableState } from "@/components/page-states";
import { loadUiCapabilityProjection } from "@/lib/server/load-ui-capability-projection.server";
import { isUploadAvailable, uploadCapability, uploadUnavailableMessage } from "@/lib/upload-capability";

/**
 * Upload is never a universal tenant destination.
 * Offered only when:
 * - deployment profile allows local-demo upload (not production BFF), AND
 * - server projects VIEW_DOCUMENTS (INTAKE_READ).
 * There is no registered production BFF upload mutation route; local demo remains bounded.
 */
export default async function Page() {
  const capability = uploadCapability();
  const projection = await loadUiCapabilityProjection();
  const mayOfferDocuments =
    projection.status === "ALLOWED" && projection.capabilities.includes("VIEW_DOCUMENTS");

  if (!isUploadAvailable(capability) || !mayOfferDocuments) {
    return (
      <DashboardShell title="Document Upload">
        <UnavailableState
          title="Upload not available"
          description={uploadUnavailableMessage()}
          reason={
            !isUploadAvailable(capability)
              ? "Manual upload is disabled for this deployment profile. Intake continues through supported channel paths when configured."
              : "Upload requires a projected documents capability. Local-demo upload is not tenant production authority."
          }
        />
      </DashboardShell>
    );
  }

  return (
    <DashboardShell title="Document Upload">
      <section className="panel">
        <h2>Manual file intake</h2>
        <p>
          Local-test upload only. Allowed: PDF, CSV, XLS, XLSX, PNG, JPEG, and plain text. Default max
          size: 10 MB. Files are stored as untrusted objects and queued for future processing.
        </p>
        <p className="risk-note">
          This form is not a production upload authority. Production BFF has no registered intake upload
          mutation route.
        </p>
      </section>
      <section className="panel">
        <h2>Upload result</h2>
        <p>
          Local demo uploads send only business input; no direct database or object-store access is allowed
          from the frontend.
        </p>
      </section>
      <IntakeUploadForm />
    </DashboardShell>
  );
}
