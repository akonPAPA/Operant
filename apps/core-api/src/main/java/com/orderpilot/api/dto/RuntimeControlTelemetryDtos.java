package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * OP-CAP-27D — public, tenant-operator-safe Runtime Control Telemetry contracts for the RFQ/AI/demo
 * path.
 *
 * <p>This read model makes the PR #244 runtime-control posture of the visible demo flow inspectable
 * without exposing any runtime internals. It is a tenant-gated read of the default/static contract
 * posture — NOT tenant-specific entitlement/quota/rate-bucket telemetry or production denial-rate
 * telemetry. It never carries tenant, actor, source-event, audit, idempotency, provider, connector,
 * plan, quota-bucket, rate-window, or raw guard-state fields.
 *
 * <p>Every measurable metric is wrapped in a {@link TelemetryValue} that self-labels its
 * {@link MeasurementKind} so the UI can tell apart a really-observed measurement, a static contract
 * posture, a value that is deliberately not measured in this slice, and a value that does not apply to
 * the demo path. Nothing here is a fake zero.
 */
public final class RuntimeControlTelemetryDtos {
  private RuntimeControlTelemetryDtos() {}

  /** How a telemetry value should be read by an operator. */
  public enum MeasurementKind {
    /** A value genuinely observed/counted from persisted state. */
    MEASURED,
    /** A stable, deterministic contract/config posture (not a runtime observation). */
    STATIC_CONTRACT,
    /** Not persisted or aggregated in this slice; deliberately blank, never a fake zero. */
    NOT_MEASURED,
    /** The dimension does not apply to the demo path (e.g. billing/quota on rate-only ops). */
    NOT_APPLICABLE
  }

  /**
   * A single self-describing telemetry cell. {@code value} is a display string for MEASURED /
   * STATIC_CONTRACT and is {@code null} for NOT_MEASURED / NOT_APPLICABLE.
   */
  public record TelemetryValue(MeasurementKind kind, String value, String explanation) {
    public static TelemetryValue staticContract(String value, String explanation) {
      return new TelemetryValue(MeasurementKind.STATIC_CONTRACT, value, explanation);
    }

    public static TelemetryValue measured(String value, String explanation) {
      return new TelemetryValue(MeasurementKind.MEASURED, value, explanation);
    }

    public static TelemetryValue notMeasured(String explanation) {
      return new TelemetryValue(MeasurementKind.NOT_MEASURED, null, explanation);
    }

    public static TelemetryValue notApplicable(String explanation) {
      return new TelemetryValue(MeasurementKind.NOT_APPLICABLE, null, explanation);
    }
  }

  public record RuntimeControlDemoFlowTelemetryResponse(
      Instant generatedAt,
      String scopeLabel,
      SafetyPosture safety,
      List<WorkloadPosture> workloadPostures,
      AdmissionPosture admission,
      List<ProvenGuarantee> provenGuarantees,
      List<NotMeasured> notMeasured) {}

  /**
   * Explicit safety framing for the read: this endpoint only observes posture, it never runs the
   * guard, calls a connector, or performs an external write.
   */
  public record SafetyPosture(
      String runtimeControlView,
      String connectorInvocation,
      String externalExecution,
      String guardEvaluation,
      String telemetryCompleteness,
      String statement) {}

  /**
   * Per demo-path checkpoint posture. {@code pathStep} is a stable token; the four values describe the
   * workload type, sync/async posture, cheap-vs-AI cost path, and the guard tier that protects it.
   */
  public record WorkloadPosture(
      String pathStep,
      String label,
      TelemetryValue workloadType,
      TelemetryValue executionPosture,
      TelemetryValue costPath,
      TelemetryValue guardPosture) {}

  /**
   * Admission-control posture. The threshold cells are STATIC_CONTRACT config; the admitted/denied
   * counters are NOT_MEASURED because runtime-control admission is deterministic and side-effect-free —
   * no admission/denial counters are persisted in this slice.
   */
  public record AdmissionPosture(
      TelemetryValue runtimeControlEnabled,
      TelemetryValue aiWorkloadEnabled,
      TelemetryValue maxCostUnitsPerRequest,
      TelemetryValue maxSyncCostUnits,
      TelemetryValue backpressureQueueDepth,
      TelemetryValue admittedCount,
      TelemetryValue deniedCount) {}

  public record ProvenGuarantee(String code, String label, String statement) {}

  public record NotMeasured(String code, String label, String explanation) {}
}
