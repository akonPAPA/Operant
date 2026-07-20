import { DashboardShell } from "@/components/dashboard-shell";
import { getIntakeDocuments } from "@/lib/server/intake-api.server";
import { AccessDeniedState, EmptyState, ErrorState } from "@/components/page-states";
import Link from "next/link";

export default async function Page() {
  const { data: documents, error, code } = await getIntakeDocuments();
  // Distinguish authorization denial, contract drift, and dependency/read failure.
  // `error`/`code` come from the redacted public-error mapper — never a raw Core body/stack/URL.
  const accessDenied = code === "ACCESS_DENIED" || code === "AUTH_REQUIRED";
  const contractError = code === "CONTRACT_ERROR";

  return (
    <DashboardShell title="Documents">
      <section className="panel">
        <h2>Document intake</h2>
        <p>Documents are stored through the object storage abstraction, fingerprinted for duplicate detection, and queued for future processing without OCR or AI extraction.</p>
      </section>
      {error && accessDenied ? (
        <AccessDeniedState description="You do not have access to tenant document intake." />
      ) : error && contractError ? (
        <ErrorState
          title="Response could not be understood"
          description="The documents response did not match the expected contract. No raw server body is shown."
          action={
            <Link className="secondary-button table-link-button" href="/documents">
              Retry
            </Link>
          }
        />
      ) : error ? (
        <ErrorState
          title="Backend data unavailable"
          description={error}
          action={
            // Idempotent read retry: reloads the same GET route; no mutation, no request loop.
            <Link className="secondary-button table-link-button" href="/documents">
              Retry
            </Link>
          }
        />
      ) : documents.length === 0 ? (
        <EmptyState title="No documents yet" description="No documents have been received for this tenant." />
      ) : (
        <section className="panel table-panel">
          <table className="data-table">
            <thead><tr><th>Filename</th><th>Type</th><th>Source</th><th>Size</th><th>Status</th><th>Received</th></tr></thead>
            <tbody>
              {documents.map((document) => (
                <tr key={document.id}>
                  <td><Link href={`/documents/${document.id}`}>{document.originalFilename ?? document.id}</Link></td>
                  <td>{document.contentType ?? document.documentType}</td>
                  <td>{document.sourceChannel}</td>
                  <td>{document.fileSizeBytes ?? 0}</td>
                  <td>{document.status}</td>
                  <td>{new Date(document.receivedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </DashboardShell>
  );
}
