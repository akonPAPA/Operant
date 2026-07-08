package com.orderpilot.application.services.runtime;

import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.AdmissionPosture;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.NotMeasured;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.ProvenGuarantee;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.RuntimeControlDemoFlowTelemetryResponse;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.SafetyPosture;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.TelemetryValue;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.WorkloadPosture;
import com.orderpilot.common.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-27D — tenant-scoped, read-only Runtime Control Telemetry read model for the RFQ/AI/demo path.
 *
 * <p>This service is deliberately side-effect-free. It resolves the tenant only to prove the read is
 * tenant-scoped (the value is never returned), reads the deterministic {@link RuntimeControlProperties}
 * contract posture, and emits static + not-measured telemetry cells. It never invokes the runtime
 * guard/admission pipeline, never touches a business/audit/idempotency/outbox/connector table, and never
 * fabricates a denial/admission count — because runtime-control admission is deterministic and records
 * no counters, those are honestly labelled {@code NOT_MEASURED}.
 */
@Service
public class RuntimeControlDemoFlowTelemetryService {
  private static final String SCOPE_LABEL =
      "Runtime-control default contract posture for the RFQ/AI/demo path (tenant-gated read; partial"
          + " telemetry). Tenant-specific entitlement, quota-bucket, rate-window, and admission/denial"
          + " counters are not measured in this slice.";

  private final RuntimeControlProperties properties;
  private final Clock clock;

  public RuntimeControlDemoFlowTelemetryService(RuntimeControlProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public RuntimeControlDemoFlowTelemetryResponse readDemoFlowTelemetry() {
    // Enforce tenant scope. The tenant id is intentionally not exposed in the response.
    TenantContext.requireTenantId();
    return new RuntimeControlDemoFlowTelemetryResponse(
        clock.instant(),
        SCOPE_LABEL,
        safety(),
        workloadPostures(),
        admission(),
        provenGuarantees(),
        notMeasured());
  }

  private static SafetyPosture safety() {
    return new SafetyPosture(
        "READ_ONLY",
        "NOT_INVOKED",
        "DISABLED",
        "NOT_INVOKED_BY_THIS_READ",
        "PARTIAL",
        "Read-only, tenant-gated runtime-control posture. This endpoint does not invoke the admission"
            + " guard, call a connector, or perform any external execution. It shows the default/static"
            + " contract posture only: tenant-specific entitlement, quota-bucket, rate-window, and"
            + " admission/denial counters are not measured in this slice — this is not production"
            + " denial-rate telemetry.");
  }

  private static List<WorkloadPosture> workloadPostures() {
    return List.of(
        new WorkloadPosture(
            "DEMO_RFQ_HANDOFF_CREATE",
            "Demo RFQ handoff creation",
            TelemetryValue.staticContract(
                "DETERMINISTIC_DEMO_OP",
                "Operator-initiated deterministic demo write; not an AI workload."),
            TelemetryValue.staticContract(
                "SYNC", "Runs synchronously on the cheap deterministic path."),
            TelemetryValue.staticContract(
                "CHEAP_PATH", "No model/provider call; deterministic-only cost path."),
            TelemetryValue.staticContract(
                "RATE_BACKPRESSURE_GATED",
                "Guarded by rate + queue-backpressure admission only (no quota/plan coupling).")),
        new WorkloadPosture(
            "RFQ_HANDOFF_AI_ADVISORY",
            "RFQ handoff AI advisory suggestion",
            TelemetryValue.staticContract(
                "AI_VALIDATION_ASSIST", "Advisory AI explanation workload (suggests only, never writes)."),
            TelemetryValue.staticContract(
                "SYNC_WITH_ASYNC_PROMOTION",
                "Deterministic classifier may promote to async above the sync cost threshold."),
            TelemetryValue.staticContract(
                "AI_PATH", "Reaches the advisory model/provider boundary."),
            TelemetryValue.staticContract(
                "ENTITLEMENT_QUOTA_RATE_GATED",
                "Guarded before the provider call by the shared AI_VALIDATION_EXPLANATION guard"
                    + " (entitlement -> quota -> rate).")),
        new WorkloadPosture(
            "RFQ_HANDOFF_DRAFT_QUOTE_CREATE",
            "Review-required draft quote creation",
            TelemetryValue.staticContract(
                "DETERMINISTIC_DEMO_OP", "Deterministic draft assembly; not an AI workload."),
            TelemetryValue.staticContract("SYNC", "Runs synchronously on the cheap deterministic path."),
            TelemetryValue.staticContract(
                "CHEAP_PATH", "No model/provider call; deterministic-only cost path."),
            TelemetryValue.staticContract(
                "RATE_BACKPRESSURE_GATED",
                "Guarded by rate + queue-backpressure admission only (no quota/plan coupling).")),
        new WorkloadPosture(
            "RFQ_HANDOFF_DEMO_DECISION",
            "Safe terminal demo decision",
            TelemetryValue.staticContract(
                "DETERMINISTIC_DEMO_OP", "Deterministic safe-terminal decision; not an AI workload."),
            TelemetryValue.staticContract("SYNC", "Runs synchronously on the cheap deterministic path."),
            TelemetryValue.staticContract(
                "CHEAP_PATH", "No model/provider call; deterministic-only cost path."),
            TelemetryValue.staticContract(
                "RATE_BACKPRESSURE_GATED",
                "Guarded by rate + queue-backpressure admission only (no quota/plan coupling).")));
  }

  private AdmissionPosture admission() {
    return new AdmissionPosture(
        TelemetryValue.staticContract(
            properties.isEnabled() ? "ENABLED" : "DISABLED",
            "Whether runtime-control admission is enabled for this environment (contract default)."),
        TelemetryValue.staticContract(
            properties.isDefaultAiEnabled() ? "ENABLED" : "DISABLED",
            "Whether AI-backed runtime work is enabled (gates the advisory path; contract default)."),
        TelemetryValue.staticContract(
            Long.toString(properties.getDefaultMaxCostUnitsPerRequest()),
            "Per-request cost-unit ceiling above which a request is denied (contract default)."),
        TelemetryValue.staticContract(
            Long.toString(properties.getDefaultMaxSyncCostUnits()),
            "Cost-unit ceiling above which an allowed request is promoted to async (contract default)."),
        TelemetryValue.staticContract(
            Integer.toString(properties.getDefaultBackpressureQueueDepth()),
            "Queue depth at which backpressure denies with a retry-after (contract default)."),
        TelemetryValue.notMeasured(
            "Admitted-request counts are not persisted; runtime-control admission is deterministic and"
                + " side-effect-free in this slice."),
        TelemetryValue.notMeasured(
            "Denied-request counts are not persisted; no admission/denial telemetry is aggregated in"
                + " this slice."));
  }

  private static List<ProvenGuarantee> provenGuarantees() {
    return List.of(
        new ProvenGuarantee(
            "ALL_DEMO_CHECKPOINTS_GUARDED",
            "All four RFQ/AI/demo checkpoints are guarded before side effects",
            "PR #244 guards demo RFQ handoff creation, draft quote creation, and the safe terminal"
                + " decision; the AI advisory boundary is guarded by the shared AI_VALIDATION_EXPLANATION"
                + " guard."),
        new ProvenGuarantee(
            "DENIAL_FAILS_CLOSED",
            "Denial fails closed with no business/external mutation",
            "A runtime-control denial short-circuits before any business write, audit, idempotency,"
                + " outbox, connector, or external ERP/1C effect."),
        new ProvenGuarantee(
            "READ_ONLY_TELEMETRY",
            "This telemetry read invokes no admission guard",
            "The read model observes contract posture only; it does not run the guard, so reading"
                + " telemetry can never admit, deny, or mutate anything."));
  }

  private static List<NotMeasured> notMeasured() {
    return List.of(
        new NotMeasured(
            "TENANT_SPECIFIC_RUNTIME_POLICY_NOT_MEASURED",
            "Tenant-specific runtime policy",
            "The posture shown is the default/static runtime-control contract; this slice does not read"
                + " the tenant's specific runtime plan or feature-entitlement state."),
        new NotMeasured(
            "TENANT_RATE_BUCKET_STATE_NOT_MEASURED",
            "Tenant rate-window state",
            "The tenant's live rate-limit window/bucket state is not read or reported in this slice."),
        new NotMeasured(
            "TENANT_QUOTA_BUCKET_STATE_NOT_MEASURED",
            "Tenant quota-bucket state",
            "The tenant's live quota-bucket consumption is not read or reported in this slice."),
        new NotMeasured(
            "RUNTIME_ADMISSION_DENIAL_COUNTERS_NOT_MEASURED",
            "Runtime admission/denial counts",
            "Admission and denial counts are not persisted or aggregated for the demo path in this"
                + " slice; they are labelled NOT_MEASURED, never a fake zero."),
        new NotMeasured(
            "DISTRIBUTED_RUNTIME_GUARD_NOT_PROVEN",
            "Distributed runtime guard",
            "The default rate store is per-instance; multi-node runtime-control behaviour is out of"
                + " scope for this read model."),
        new NotMeasured(
            "PROVIDER_RUNTIME_BILLING_NOT_MEASURED",
            "Provider-specific runtime billing",
            "Provider/model runtime accounting and billing dimensions are not applicable to the"
                + " rate-only demo operations and are not measured."),
        new NotMeasured(
            "ALL_CHANNEL_DENIAL_TELEMETRY_NOT_MEASURED",
            "Cross-channel denial telemetry",
            "Only the RFQ/AI/demo path is described; runtime denial telemetry for all channels is not"
                + " covered."),
        new NotMeasured(
            "SUPPORT_STAFF_TELEMETRY_PLANE_NOT_PROVEN",
            "Support/staff telemetry plane",
            "This is a tenant-operator read; no Operant staff/support telemetry plane is exposed."));
  }
}
