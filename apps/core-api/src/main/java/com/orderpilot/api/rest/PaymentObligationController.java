package com.orderpilot.api.rest;

import com.orderpilot.api.dto.PaymentObligationDtos.CustomerPaymentSummaryResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationResponse;
import com.orderpilot.application.services.payment.PaymentObligationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Narrow, read-only, tenant-scoped payment obligation read surface. It lives under the existing
 * {@code /api/v1/trust} prefix so it is automatically guarded by {@code TRUST_READ}; payment status is
 * treated as transaction-trust intelligence. Tenant is resolved from context inside the service; the
 * path counterparty/obligation id is never trusted across tenants. No raw bank/PSP/card references or
 * audit payloads are ever returned. All mutation of payment state happens only through the backend
 * command service — never through this API.
 */
@RestController
public class PaymentObligationController {
  private final PaymentObligationService paymentObligationService;

  public PaymentObligationController(PaymentObligationService paymentObligationService) {
    this.paymentObligationService = paymentObligationService;
  }

  @GetMapping("/api/v1/trust/counterparties/{customerAccountId}/payment-summary")
  public CustomerPaymentSummaryResponse getPaymentSummary(@PathVariable UUID customerAccountId) {
    return paymentObligationService.getCustomerPaymentSummary(customerAccountId);
  }

  @GetMapping("/api/v1/trust/counterparties/{customerAccountId}/payment-obligations")
  public List<PaymentObligationResponse> listPaymentObligations(
      @PathVariable UUID customerAccountId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    return paymentObligationService.listCustomerObligations(customerAccountId, status, limit);
  }

  @GetMapping("/api/v1/trust/payment-obligations/{obligationId}")
  public PaymentObligationResponse getPaymentObligation(@PathVariable UUID obligationId) {
    return paymentObligationService.getObligationView(obligationId);
  }
}
