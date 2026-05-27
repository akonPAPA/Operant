import type { Stage9ConnectorSyncRun } from "@/lib/stage9-integration-api";

export function ConnectorSyncRuns({ syncRuns }: Readonly<{ syncRuns: Stage9ConnectorSyncRun[] }>) {
  return (
    <section className="panel">
      <h2>Connector Sync Runs</h2>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Provider</th>
              <th>Type</th>
              <th>Direction</th>
              <th>Status</th>
              <th>Records</th>
            </tr>
          </thead>
          <tbody>
            {syncRuns.length === 0 ? (
              <tr><td colSpan={5}>No connector sync runs yet.</td></tr>
            ) : syncRuns.map((run) => (
              <tr key={run.id}>
                <td>{run.providerType}</td>
                <td>{run.syncType}</td>
                <td>{run.direction}</td>
                <td>{run.status}{run.errorCode ? ` / ${run.errorCode}` : ""}</td>
                <td>read {run.recordsRead} / written {run.recordsWritten} / failed {run.recordsFailed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="risk-note">Connector audit timeline is recorded through tenant-scoped audit events and local sync-run records. Demo ERP runs do not call external networks.</p>
    </section>
  );
}
