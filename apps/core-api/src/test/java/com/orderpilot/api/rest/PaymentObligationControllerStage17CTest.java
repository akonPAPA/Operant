package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.PaymentObligationDtos.CustomerPaymentSummaryResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationEventResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationResponse;
import com.orderpilot.application.services.payment.PaymentObligationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation — read endpoint contract and safety.
 */
@WebMvcTest(PaymentObligationController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class PaymentObligationControllerStage17CTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private PaymentObligationService service;

  private PaymentObligationResponse obligation(UUID id, UUID cp) {
    return new PaymentObligationResponse(
        id, cp, "INV-001", "EXT-REF",
        new BigDecimal("100.0000"), new BigDecimal("40.0000"), new BigDecimal("60.0000"), "USD",
        LocalDate.of(2026, 12, 31), "PARTIALLY_PAID", "MEDIUM",
        Instant.parse("2026-06-14T10:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"),
        Instant.parse("2026-06-14T10:00:00Z"),
        List.of(new PaymentObligationEventResponse("PAYMENT_ALLOCATED", "OPEN", "PARTIALLY_PAID",
            new BigDecimal("40.0000"), new BigDecimal("60.0000"), "Payment allocated",
            Instant.parse("2026-06-14T10:00:00Z"))));
  }

  private CustomerPaymentSummaryResponse summary(UUID cp) {
    return new CustomerPaymentSummaryResponse(
        cp, "USD", new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("50.0000"),
        1, 0, 1, 0, 1, 0, 0, Instant.parse("2026-06-14T10:00:00Z"), 70, List.of());
  }

  @Test
  void authorizedReadReturnsObligation() throws Exception {
    UUID id = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    when(service.getObligationView(eq(id))).thenReturn(obligation(id, cp));

    mockMvc.perform(get("/api/v1/trust/payment-obligations/" + id).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
        .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
        .andExpect(jsonPath("$.amountRemaining").value(60.0000))
        .andExpect(jsonPath("$.recentEvents[0].eventType").value("PAYMENT_ALLOCATED"));
  }

  @Test
  void authorizedReadReturnsPaymentSummary() throws Exception {
    UUID cp = UUID.randomUUID();
    when(service.getCustomerPaymentSummary(eq(cp))).thenReturn(summary(cp));

    mockMvc.perform(get("/api/v1/trust/counterparties/" + cp + "/payment-summary")
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerAccountId").value(cp.toString()))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.overdueCount").value(1))
        .andExpect(jsonPath("$.paymentReliabilityScore").value(70));
  }

  @Test
  void responseDoesNotExposeSensitiveFields() throws Exception {
    UUID id = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    when(service.getObligationView(eq(id))).thenReturn(obligation(id, cp));

    String body = mockMvc.perform(get("/api/v1/trust/payment-obligations/" + id)
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body)
        .doesNotContain("iban").doesNotContain("IBAN")
        .doesNotContain("pan").doesNotContain("PAN")
        .doesNotContain("cvv").doesNotContain("CVV")
        .doesNotContain("routingNumber")
        .doesNotContain("accountNumber")
        .doesNotContain("bankCredential")
        .doesNotContain("cardNumber")
        .doesNotContain("nfcPayload")
        .doesNotContain("fingerprint")
        .doesNotContain("sha256");
  }

  @Test
  void unknownObligationReturnsNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getObligationView(eq(id))).thenThrow(new NotFoundException("Payment obligation not found"));

    mockMvc.perform(get("/api/v1/trust/payment-obligations/" + id).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void listForwardsRawLimitAndStatusForClamping() throws Exception {
    UUID cp = UUID.randomUUID();
    when(service.listCustomerObligations(eq(cp), isNull(), eq(9999))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/trust/counterparties/" + cp + "/payment-obligations?limit=9999")
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());

    // The service clamps; the controller forwards the raw requested limit and null (absent) status.
    verify(service).listCustomerObligations(cp, null, 9999);
  }
}
