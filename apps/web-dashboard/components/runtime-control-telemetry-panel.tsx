import Link from "next/link";

import type {
  RuntimeControlDemoFlowTelemetry,
  RuntimeControlTelemetryValue
} from "@/lib/runtime-control-telemetry-api";

type Props = {
  data: RuntimeControlDemoFlowTelemetry | null;
  error?: string;
};

function formatTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "Not available" : parsed.toLocaleString();
}

function humanize(value: string): string {
  return value ? value.toLowerCase().replaceAll("_", " ") : "Not available";
}

// A telemetry cell renders its measured/static value, or an honest "Not measured" / "Not applicable"
// label when the backend did not (and must not fake) a value. It never blindly stringifies the object.
function TelemetryCell({ metric }: { metric: RuntimeControlTelemetryValue }) {
  const measured = metric.kind === "MEASURED" || metric.kind === "STATIC_CONTRACT";
  const label = measured
    ? humanize(metric.value ?? "")
    : metric.kind === "NOT_MEASURED"
      ? "Not measured"
      : "Not applicable";
  const pillClass = measured ? "status-pill done" : "status-pill muted";
  return (
    <>
      <span className={pillClass}>{label}</span>
      <br />
      <span className="muted-copy">{metric.explanation}</span>
    </>
  );
}

export function RuntimeControlTelemetryPanel({ data, error }: Props) {
  if (!data) {
    return (
      <section className="panel">
        <h2>Runtime-control telemetry unavailable</h2>
        <p className="risk-note">
          {error ?? "No runtime-control posture is available for this tenant."}
        </p>
        <div className="button-row">
          <Link className="button secondary" href="/commerce-intelligence">
            Open Commerce Intelligence
          </Link>
          <Link className="button secondary" href="/channels/rfq-handoffs">
            Open RFQ handoffs
          </Link>
        </div>
      </section>
    );
  }

  const { safety, admission, workloadPostures, provenGuarantees, notMeasured } = data;

  return (
    <div className="demo-stack">
      {error ? <p className="risk-note">{error}</p> : null}

      <section className="panel">
        <h2>Runtime-control telemetry (RFQ/AI/demo path)</h2>
        <p className="status-pill done">
          Read-only runtime-control view · no connector invoked · external execution disabled · telemetry
          may be partial
        </p>
        <p>{safety.statement}</p>
        <p className="muted-copy">
          {data.scopeLabel}. Generated {formatTime(data.generatedAt)}.
        </p>
      </section>

      <section className="panel table-panel">
        <h2>Safety posture</h2>
        <table className="data-table">
          <thead>
            <tr><th>Boundary</th><th>State</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Runtime-control view</td>
              <td><span className="status-pill done">{safety.runtimeControlView}</span></td>
            </tr>
            <tr>
              <td>Connector invocation</td>
              <td><span className="status-pill done">{safety.connectorInvocation}</span></td>
            </tr>
            <tr>
              <td>External execution</td>
              <td><span className="status-pill done">{safety.externalExecution}</span></td>
            </tr>
            <tr>
              <td>Guard evaluation by this read</td>
              <td><span className="status-pill done">{safety.guardEvaluation}</span></td>
            </tr>
            <tr>
              <td>Telemetry completeness</td>
              <td><span className="status-pill muted">{safety.telemetryCompleteness}</span></td>
            </tr>
          </tbody>
        </table>
      </section>

      <section className="panel table-panel">
        <h2>Path-step runtime posture</h2>
        {workloadPostures.length === 0 ? (
          <p>No runtime-control path steps are described for this tenant.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Path step</th>
                <th>Workload type</th>
                <th>Sync / async</th>
                <th>Cheap / AI path</th>
                <th>Guard posture</th>
              </tr>
            </thead>
            <tbody>
              {workloadPostures.map((step) => (
                <tr key={step.pathStep}>
                  <td>{step.label}</td>
                  <td><TelemetryCell metric={step.workloadType} /></td>
                  <td><TelemetryCell metric={step.executionPosture} /></td>
                  <td><TelemetryCell metric={step.costPath} /></td>
                  <td><TelemetryCell metric={step.guardPosture} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel table-panel">
        <h2>Admission &amp; backpressure posture</h2>
        <table className="data-table">
          <thead>
            <tr><th>Dimension</th><th>Value</th></tr>
          </thead>
          <tbody>
            <tr><td>Runtime control enabled</td><td><TelemetryCell metric={admission.runtimeControlEnabled} /></td></tr>
            <tr><td>AI workload enabled</td><td><TelemetryCell metric={admission.aiWorkloadEnabled} /></td></tr>
            <tr><td>Max cost units / request</td><td><TelemetryCell metric={admission.maxCostUnitsPerRequest} /></td></tr>
            <tr><td>Max synchronous cost units</td><td><TelemetryCell metric={admission.maxSyncCostUnits} /></td></tr>
            <tr><td>Backpressure queue depth</td><td><TelemetryCell metric={admission.backpressureQueueDepth} /></td></tr>
            <tr><td>Admitted requests</td><td><TelemetryCell metric={admission.admittedCount} /></td></tr>
            <tr><td>Denied requests</td><td><TelemetryCell metric={admission.deniedCount} /></td></tr>
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Runtime-control guarantees proven for the demo path</h2>
        {provenGuarantees.length === 0 ? (
          <p>No runtime-control guarantees are recorded for this tenant.</p>
        ) : (
          <ul>
            {provenGuarantees.map((item) => (
              <li key={item.code}><strong>{item.label}:</strong> {item.statement}</li>
            ))}
          </ul>
        )}
      </section>

      <section className="panel">
        <h2>Not measured by this read model</h2>
        <ul>
          {notMeasured.map((item) => (
            <li key={item.code}><strong>{item.label}:</strong> {item.explanation}</li>
          ))}
        </ul>
        <p className="risk-note">
          Runtime denial/admission counts are not persisted for the demo path in this slice. Nothing here
          is a measured production metric, a billing dimension, or a staff/support telemetry plane.
        </p>
      </section>
    </div>
  );
}
