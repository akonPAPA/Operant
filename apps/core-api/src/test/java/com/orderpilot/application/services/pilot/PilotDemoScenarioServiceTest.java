package com.orderpilot.application.services.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService.DemoScenario;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService.DemoScenarioPack;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService.DemoScenarioReadiness;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PilotDemoScenarioService.class, PilotShadowModeService.class, AuditEventService.class, RoiAssumptionsService.class, CoreConfiguration.class})
class PilotDemoScenarioServiceTest {
  private static final List<String> EXPECTED_ORDER = List.of(
      "TELEGRAM_RFQ_SUBSTITUTION", "PDF_PO_EXCEPTION", "DISCOUNT_MARGIN_GUARDRAIL", "INVENTORY_MISMATCH", "BAD_AI_OUTPUT_REJECTED");

  @Autowired private PilotDemoScenarioService service;
  @Autowired private PilotShadowModeService pilotShadowModeService;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void emptyTenantReturnsStableOrderedPackWithNoFakeReady() {
    TenantContext.setTenantId(UUID.randomUUID());

    DemoScenarioPack pack = service.demoScenarios();

    assertThat(pack.tenantHasPilotEvidence()).isFalse();
    assertThat(pack.scenarios()).extracting(DemoScenario::code).containsExactlyElementsOf(EXPECTED_ORDER);
    assertThat(pack.packLimitations()).isNotEmpty();
    assertThat(pack.safetyStatement()).isNotBlank();

    // Every scenario always carries safety boundaries; none is a fake production-complete READY.
    for (DemoScenario scenario : pack.scenarios()) {
      assertThat(scenario.safetyBoundaries()).isNotEmpty();
      assertThat(scenario.readiness()).isIn(DemoScenarioReadiness.PARTIAL, DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO,
          DemoScenarioReadiness.BLOCKED, DemoScenarioReadiness.NOT_AVAILABLE);
      assertThat(scenario.readinessScore()).isLessThanOrEqualTo(80); // never claims 100% complete
    }
    // With no evidence, the evidence-dependent scenarios are PARTIAL (not fake-ready).
    assertThat(scenario(pack, "TELEGRAM_RFQ_SUBSTITUTION").readiness()).isEqualTo(DemoScenarioReadiness.PARTIAL);
    assertThat(scenario(pack, "DISCOUNT_MARGIN_GUARDRAIL").readiness()).isEqualTo(DemoScenarioReadiness.PARTIAL);
    assertThat(scenario(pack, "TELEGRAM_RFQ_SUBSTITUTION").missingCapabilities()).anyMatch(m -> m.contains("seeded"));
    // The code-level safety scenario needs no seeded data.
    assertThat(scenario(pack, "BAD_AI_OUTPUT_REJECTED").readiness()).isEqualTo(DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO);
  }

  @Test
  void evidenceImprovesScenarioReadinessDeterministically() {
    TenantContext.setTenantId(UUID.randomUUID());
    pilotShadowModeService.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "SUBSTITUTION", "fix", "{}", new BigDecimal("0.8000"),
        "OUT_OF_STOCK_SUBSTITUTE", new BigDecimal("10.00"), new BigDecimal("3.00"), false, true);
    pilotShadowModeService.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "fix", "{}", new BigDecimal("0.5000"),
        "MARGIN_VIOLATION", new BigDecimal("12.00"), new BigDecimal("5.00"), false, true);

    DemoScenarioPack pack = service.demoScenarios();

    assertThat(pack.tenantHasPilotEvidence()).isTrue();
    assertThat(scenario(pack, "TELEGRAM_RFQ_SUBSTITUTION").readiness()).isEqualTo(DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO);
    assertThat(scenario(pack, "PDF_PO_EXCEPTION").readiness()).isEqualTo(DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO);
    assertThat(scenario(pack, "DISCOUNT_MARGIN_GUARDRAIL").readiness()).isEqualTo(DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO);
    // Inventory reconciliation is not yet wired into pilot evidence -> honestly PARTIAL.
    assertThat(scenario(pack, "INVENTORY_MISMATCH").readiness()).isEqualTo(DemoScenarioReadiness.PARTIAL);
  }

  @Test
  void demoScenariosAreTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    pilotShadowModeService.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "SUBSTITUTION", "fix", "{}", new BigDecimal("0.8000"),
        "OUT_OF_STOCK_SUBSTITUTE", new BigDecimal("10.00"), new BigDecimal("3.00"), false, true);

    TenantContext.setTenantId(tenantB);
    DemoScenarioPack pack = service.demoScenarios();

    assertThat(pack.tenantId()).isEqualTo(tenantB);
    assertThat(pack.tenantHasPilotEvidence()).isFalse();
    assertThat(scenario(pack, "TELEGRAM_RFQ_SUBSTITUTION").readiness()).isEqualTo(DemoScenarioReadiness.PARTIAL);
  }

  private static DemoScenario scenario(DemoScenarioPack pack, String code) {
    return pack.scenarios().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
  }
}
