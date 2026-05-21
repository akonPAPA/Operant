import { DashboardShell } from "@/components/dashboard-shell";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Message Detail">
      <section className="panel">
        <h2>Message {id}</h2>
        <p>Detail layout reserves channel, sender, received time, content, status, related attachments/documents, and processing job status.</p>
      </section>
      <section className="panel">
        <h2>Run Extraction</h2>
        <p>Creates an advisory extraction run for this message. It does not create an order, quote, ERP write, or product/customer update.</p>
      </section>
    </DashboardShell>
  );
}
