import { DashboardShell } from "@/components/dashboard-shell";

export default function Page({ params }: Readonly<{ params: { id: string } }>) {
  return (
    <DashboardShell title="Message Detail">
      <section className="panel">
        <h2>Message {params.id}</h2>
        <p>Detail layout reserves channel, sender, received time, content, status, related attachments/documents, and processing job status.</p>
      </section>
      <section className="panel">
        <h2>Run Extraction</h2>
        <p>Creates an advisory extraction run for this message. It does not create an order, quote, ERP write, or product/customer update.</p>
      </section>
    </DashboardShell>
  );
}