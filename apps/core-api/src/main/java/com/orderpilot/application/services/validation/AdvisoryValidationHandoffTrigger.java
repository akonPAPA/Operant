package com.orderpilot.application.services.validation;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.extraction.AdvisoryValidationHandoffRequested;
import com.orderpilot.common.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * OP-CAP-13B — after-commit trigger that runs the 13A advisory→validation handoff once the AI-worker
 * intake transaction has durably committed.
 *
 * <p>Running after commit keeps the handoff strictly isolated from intake: the advisory result is
 * already persisted, the deterministic validation run reads a committed row, and any handoff failure
 * is contained here (recorded with a bounded reason) without rolling back or corrupting the intake.
 * The handoff itself is fail-closed and creates only advisory rows + deterministic validation
 * artifacts — never a quote/order/inventory/customer/price/connector/ERP write.
 */
@Component
public class AdvisoryValidationHandoffTrigger {
  private final AdvisoryExtractionValidationHandoffService handoffService;
  private final AuditEventService auditEventService;
  private final JsonSupport json;

  public AdvisoryValidationHandoffTrigger(
      AdvisoryExtractionValidationHandoffService handoffService,
      AuditEventService auditEventService,
      JsonSupport json) {
    this.handoffService = handoffService;
    this.auditEventService = auditEventService;
    this.json = json;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAdvisoryValidationHandoffRequested(AdvisoryValidationHandoffRequested event) {
    // The trusted tenant travels on the event; set it explicitly so the handoff is correctly scoped
    // regardless of ambient thread state, then restore the previous context.
    Optional<UUID> previous = TenantContext.getTenantId();
    TenantContext.setTenantId(event.tenantId());
    try {
      handoffService.handoff(event.extractionResultId());
    } catch (RuntimeException ex) {
      // The advisory result is already committed; a handoff failure must never propagate or mutate
      // business state. Record a bounded reason token only — no payload, document text, or secrets.
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("extractionResultId", String.valueOf(event.extractionResultId()));
      metadata.put("jobId", String.valueOf(event.jobId()));
      metadata.put("workerStatus", event.workerStatus());
      metadata.put("reason", "auto_trigger_handoff_failed");
      metadata.put("advisoryOnly", true);
      auditEventService.record("advisory_validation_handoff.auto_trigger_failed", "extraction_result",
          String.valueOf(event.extractionResultId()), null, json.writeObject(metadata));
    } finally {
      previous.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear);
    }
  }
}
