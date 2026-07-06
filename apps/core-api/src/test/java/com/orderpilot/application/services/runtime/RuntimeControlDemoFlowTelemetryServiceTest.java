package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.MeasurementKind;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.RuntimeControlDemoFlowTelemetryResponse;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.WorkloadPosture;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-27D — proves the runtime-control telemetry read model is tenant-scoped, side-effect-free, and
 * labels every metric honestly (STATIC_CONTRACT thresholds, NOT_MEASURED counters, four guarded demo
 * checkpoints), reflecting the {@link RuntimeControlProperties} contract without invoking the guard.
 */
class RuntimeControlDemoFlowTelemetryServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final RuntimeControlProperties properties = new RuntimeControlProperties();
  private final RuntimeControlDemoFlowTelemetryService service =
      new RuntimeControlDemoFlowTelemetryService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void tenantScopedReadReturnsStaticPostureAndHonestNotMeasuredCounters() {
    TenantContext.setTenantId(TENANT);

    RuntimeControlDemoFlowTelemetryResponse response = service.readDemoFlowTelemetry();

    assertThat(response.generatedAt()).isEqualTo(NOW);
    // Safety framing is explicit and read-only.
    assertThat(response.safety().runtimeControlView()).isEqualTo("READ_ONLY");
    assertThat(response.safety().externalExecution()).isEqualTo("DISABLED");
    assertThat(response.safety().connectorInvocation()).isEqualTo("NOT_INVOKED");
    assertThat(response.safety().guardEvaluation()).isEqualTo("NOT_INVOKED_BY_THIS_READ");
    assertThat(response.safety().telemetryCompleteness()).isEqualTo("PARTIAL");

    // All four RFQ/AI/demo checkpoints are described.
    assertThat(response.workloadPostures())
        .extracting(WorkloadPosture::pathStep)
        .containsExactly(
            "DEMO_RFQ_HANDOFF_CREATE",
            "RFQ_HANDOFF_AI_ADVISORY",
            "RFQ_HANDOFF_DRAFT_QUOTE_CREATE",
            "RFQ_HANDOFF_DEMO_DECISION");

    // The AI advisory path is the AI cost path with the entitlement/quota/rate guard; the deterministic
    // demo ops are the cheap path with rate/backpressure only.
    WorkloadPosture aiAdvisory = response.workloadPostures().get(1);
    assertThat(aiAdvisory.costPath().value()).isEqualTo("AI_PATH");
    assertThat(aiAdvisory.guardPosture().value()).isEqualTo("ENTITLEMENT_QUOTA_RATE_GATED");
    WorkloadPosture demoCreate = response.workloadPostures().get(0);
    assertThat(demoCreate.costPath().value()).isEqualTo("CHEAP_PATH");
    assertThat(demoCreate.guardPosture().value()).isEqualTo("RATE_BACKPRESSURE_GATED");

    // Threshold cells are STATIC_CONTRACT and reflect the properties contract, not fake observations.
    assertThat(response.admission().runtimeControlEnabled().kind())
        .isEqualTo(MeasurementKind.STATIC_CONTRACT);
    assertThat(response.admission().maxCostUnitsPerRequest().value())
        .isEqualTo(Long.toString(properties.getDefaultMaxCostUnitsPerRequest()));
    assertThat(response.admission().backpressureQueueDepth().value())
        .isEqualTo(Integer.toString(properties.getDefaultBackpressureQueueDepth()));

    // Admission/denial counts are NOT_MEASURED with a null value — never a fake zero.
    assertThat(response.admission().admittedCount().kind()).isEqualTo(MeasurementKind.NOT_MEASURED);
    assertThat(response.admission().admittedCount().value()).isNull();
    assertThat(response.admission().deniedCount().kind()).isEqualTo(MeasurementKind.NOT_MEASURED);
    assertThat(response.admission().deniedCount().value()).isNull();

    assertThat(response.provenGuarantees()).isNotEmpty();
    assertThat(response.notMeasured())
        .anyMatch(item -> item.code().equals("SUPPORT_STAFF_TELEMETRY_PLANE_NOT_PROVEN"));
  }

  @Test
  void disabledContractIsReflectedHonestlyAsStaticPosture() {
    properties.setEnabled(false);
    properties.setDefaultAiEnabled(false);
    TenantContext.setTenantId(TENANT);

    RuntimeControlDemoFlowTelemetryResponse response = service.readDemoFlowTelemetry();

    assertThat(response.admission().runtimeControlEnabled().kind())
        .isEqualTo(MeasurementKind.STATIC_CONTRACT);
    assertThat(response.admission().runtimeControlEnabled().value()).isEqualTo("DISABLED");
    assertThat(response.admission().aiWorkloadEnabled().value()).isEqualTo("DISABLED");
  }

  @Test
  void missingTenantContextFailsClosed() {
    // No tenant set: the read must fail closed rather than leak a cross-tenant/global posture.
    assertThatThrownBy(service::readDemoFlowTelemetry)
        .isInstanceOf(TenantContextMissingException.class);
  }
}
