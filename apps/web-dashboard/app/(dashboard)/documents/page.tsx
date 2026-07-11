import { DashboardShell } from "@/components/dashboard-shell";
import { getIntakeDocuments } from "@/lib/server/intake-api.server";
import Link from "next/link";

export default async function Page() {
  const { data: documents, error } = await getIntakeDocuments();
  return (
    <DashboardShell title="Documents">
      <section className="panel">
        <h2>Document intake</h2>
        <p>Documents are stored through the object storage abstraction, fingerprinted for duplicate detection, and queued for future processing without OCR or AI extraction.</p>
      </section>
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Filename</th><th>Type</th><th>Source</th><th>Size</th><th>Status</th><th>Received</th></tr></thead>
          <tbody>
            {documents.length === 0 ? <tr><td colSpan={6}>No documents received.</td></tr> : documents.map((document) => (
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
    </DashboardShell>
  );
}
