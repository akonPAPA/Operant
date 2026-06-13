package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustCounts;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustProfileView;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSignalView;
import com.orderpilot.application.services.trust.CounterpartyTrustProfileService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation — read endpoint contract and safety.
 */
@WebMvcTest(CounterpartyTrustController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class CounterpartyTrustControllerStage17BTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private CounterpartyTrustProfileService profileService;

  private CounterpartyTrustProfileView view(UUID cp) {
    return new CounterpartyTrustProfileView(
        cp, 62, "WATCHLIST", 80, 50, 50, "HIGH",
        new CounterpartyTrustCounts(3, 1, 0, 0, 0, 0, 0, 1),
        List.of(new CounterpartyTrustSignalView("DOCUMENT_HIGH_RISK_SIGNAL", "HIGH",
            "Document trust run produced a HIGH risk decision.", "DOCUMENT_TRUST_RUN",
            Instant.parse("2026-06-13T00:00:00Z"))),
        List.of());
  }

  @Test
  void authorizedReadReturnsProfile() throws Exception {
    UUID cp = UUID.randomUUID();
    when(profileService.getProfileView(eq(cp), eq(25), eq(25))).thenReturn(view(cp));

    mockMvc.perform(get("/api/v1/trust/counterparties/" + cp).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counterpartyId").value(cp.toString()))
        .andExpect(jsonPath("$.trustScore").value(62))
        .andExpect(jsonPath("$.trustTier").value("WATCHLIST"))
        .andExpect(jsonPath("$.lastRiskLevel").value("HIGH"))
        .andExpect(jsonPath("$.counts.highRiskDocumentCount").value(1))
        .andExpect(jsonPath("$.recentSignals[0].signalCode").value("DOCUMENT_HIGH_RISK_SIGNAL"));
  }

  @Test
  void responseDoesNotExposeSensitiveFields() throws Exception {
    UUID cp = UUID.randomUUID();
    when(profileService.getProfileView(eq(cp), eq(25), eq(25))).thenReturn(view(cp));

    String body = mockMvc.perform(get("/api/v1/trust/counterparties/" + cp)
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body)
        .doesNotContain("fingerprint")
        .doesNotContain("Hash").doesNotContain("hash")
        .doesNotContain("sha256")
        .doesNotContain("iban").doesNotContain("IBAN")
        .doesNotContain("accountNumber")
        .doesNotContain("routingNumber");
  }

  @Test
  void unknownProfileReturnsNotFound() throws Exception {
    UUID cp = UUID.randomUUID();
    when(profileService.getProfileView(eq(cp), eq(25), eq(25)))
        .thenThrow(new NotFoundException("Counterparty trust profile not found"));

    mockMvc.perform(get("/api/v1/trust/counterparties/" + cp).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void signalsEndpointForwardsRequestedLimitForClamping() throws Exception {
    UUID cp = UUID.randomUUID();
    when(profileService.listRecentSignals(eq(cp), eq(9999))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/trust/counterparties/" + cp + "/signals?limit=9999")
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());

    // The service is responsible for clamping; the controller must forward the raw requested limit.
    verify(profileService).listRecentSignals(cp, 9999);
  }
}
