import type { Stage9ChangeRequest } from "@/lib/stage9-integration-api";

export function ChangeRequestQueue({ changeRequests }: Readonly<{ changeRequests: Stage9ChangeRequest[] }>) {
  return (
    <section className="panel">
      <h2>ChangeRequest queue</h2>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Target</th>
              <th>Source</th>
              <th>Approval</th>
              <th>Status</th>
              <th>External reference</th>
            </tr>
          </thead>
          <tbody>
            {changeRequests.length === 0 ? (
              <tr><td colSpan={5}>No demo ERP ChangeRequests yet.</td></tr>
            ) : changeRequests.map((request) => (
              <tr key={request.id}>
                <td>{request.targetSystem} / {request.requestedAction}</td>
                <td>{request.sourceType}</td>
                <td>{request.approvalStatus}</td>
                <td>{request.status}</td>
                <td>{request.externalReference ?? "Not executed"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="risk-note">Only approved validation-backed draft quote/order ChangeRequests can execute, and Stage 9A execution is demo-adapter only.</p>
      <p className="risk-note">External execution stays disabled; the business status reflects the safe demo-adapter outcome only.</p>
    </section>
  );
}
