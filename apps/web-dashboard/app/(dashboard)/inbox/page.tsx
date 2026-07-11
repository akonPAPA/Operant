import { DashboardShell } from "@/components/dashboard-shell";
import { getIntakeDocuments, getIntakeJobs, getIntakeMessages } from "@/lib/server/intake-api.server";

export default async function Page({ searchParams }: Readonly<{ searchParams: Promise<{ channel?: string; status?: string }> }>) {
  const [{ data: documents, error: documentError }, { data: messages, error: messageError }, { data: jobs }] = await Promise.all([
    getIntakeDocuments(),
    getIntakeMessages(),
    getIntakeJobs()
  ]);
  const filters = await searchParams;
  const rows = [
    ...messages.map((message) => ({
      id: message.id,
      receivedAt: message.receivedAt,
      channel: message.channel,
      kind: "Message",
      status: message.status,
      summary: message.textContent || message.externalMessageId || "No text"
    })),
    ...documents.map((document) => ({
      id: document.id,
      receivedAt: document.receivedAt,
      channel: document.sourceChannel,
      kind: "Document",
      status: document.status,
      summary: document.originalFilename || document.documentType
    }))
  ]
    .filter((row) => !filters.channel || filters.channel === "ALL" || row.channel === filters.channel)
    .filter((row) => !filters.status || filters.status === "ALL" || row.status === filters.status)
    .sort((a, b) => b.receivedAt.localeCompare(a.receivedAt));
  const error = documentError ?? messageError;

  return (
    <DashboardShell title="Omnichannel Inbox">
      <section className="panel">
        <h2>Unified intake queue</h2>
        <p>Inbound documents and channel messages are received, stored, deduplicated, audited, and queued for later processing. AI extraction and OCR are intentionally outside this phase.</p>
      </section>
      <section className="panel filter-row">
        {["ALL", "FILE_UPLOAD", "API", "EMAIL", "TELEGRAM", "WHATSAPP"].map((channel) => (
          <a href={`/inbox?channel=${channel}&status=${filters.status ?? "ALL"}`} key={channel}>{channel}</a>
        ))}
        {["ALL", "QUEUED", "DUPLICATE", "FAILED", "COMPLETED"].map((status) => (
          <a href={`/inbox?channel=${filters.channel ?? "ALL"}&status=${status}`} key={status}>{status}</a>
        ))}
      </section>
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Received</th><th>Channel</th><th>Kind</th><th>Status</th><th>Summary</th></tr></thead>
          <tbody>
            {rows.length === 0 ? <tr><td colSpan={5}>No inbound records match the current filters.</td></tr> : rows.map((row) => (
              <tr key={`${row.kind}-${row.id}`}>
                <td>{new Date(row.receivedAt).toLocaleString()}</td>
                <td>{row.channel}</td>
                <td>{row.kind}</td>
                <td>{row.status}</td>
                <td>{row.summary}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="panel">
        <h2>Processing jobs</h2>
        <p>{jobs.length} pending or historical intake jobs visible for the active tenant.</p>
      </section>
    </DashboardShell>
  );
}
