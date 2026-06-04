import { DashboardShell } from "@/components/dashboard-shell";
import { ChannelQuoteConversionPanel } from "@/components/channel-quote-conversion-panel";
import { getIntakeDocument } from "@/lib/intake-api";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const { data: document, error } = await getIntakeDocument(id);

  return (
    <DashboardShell title="Document Detail">
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel">
        <h2>{document.originalFilename ?? id}</h2>
        <dl className="detail-list">
          <div><dt>Status</dt><dd>{document.status ?? "Unknown"}</dd></div>
          <div><dt>Source</dt><dd>{document.sourceChannel ?? "Unknown"}</dd></div>
          <div><dt>Type</dt><dd>{document.contentType ?? document.documentType ?? "Unknown"}</dd></div>
          <div><dt>Size</dt><dd>{document.fileSizeBytes ?? 0} bytes</dd></div>
          <div><dt>Fingerprint</dt><dd>{document.sha256Fingerprint ?? "Not available"}</dd></div>
          <div><dt>Received</dt><dd>{document.receivedAt ? new Date(document.receivedAt).toLocaleString() : "Unknown"}</dd></div>
        </dl>
      </section>
      <section className="panel">
        <h2>Processing boundary</h2>
        <p>The uploaded object remains source evidence. Quote preparation uses backend extraction/validation results and does not approve quotes, create orders, or write to ERP.</p>
      </section>
      <ChannelQuoteConversionPanel sourceId={id} sourceType="INBOUND_DOCUMENT" />
    </DashboardShell>
  );
}
