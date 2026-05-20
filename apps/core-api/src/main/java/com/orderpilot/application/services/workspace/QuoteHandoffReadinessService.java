package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteHandoffReadinessService {
  private static final Set<String> PENDING_SUBSTITUTE_STATES = Set.of("SUBSTITUTE_SUGGESTED", "SUBSTITUTE_APPROVAL_REQUIRED");
  private static final Set<String> BLOCKED_SUBSTITUTE_STATES = Set.of("SUBSTITUTE_REJECTED", "SUBSTITUTE_BLOCKED", "NO_SAFE_SUBSTITUTE_FOUND");

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final ProductRepository productRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public QuoteHandoffReadinessService(DraftQuoteRepository quoteRepository, DraftQuoteLineRepository lineRepository, QuoteValidationIssueRepository issueRepository, ProductRepository productRepository, AuditEventService auditEventService, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.productRepository = productRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public QuoteHandoffResponse check(UUID quoteId, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = getQuote(tenantId, quoteId);
    List<String> blocking = blockingIssues(tenantId, quote);
    String status = blocking.isEmpty() ? "READY_FOR_HANDOFF" : "HANDOFF_BLOCKED";
    auditEventService.record("QUOTE_HANDOFF_READINESS_CHECKED", "DRAFT_QUOTE", quote.getId().toString(), actorId, metadata(tenantId, quote, null, null, status, String.join("; ", blocking), null, null));
    return new QuoteHandoffResponse(quote.getId(), quote.getStatus(), status, blocking, null, null, null, null, "EXECUTION_DISABLED", blocking.isEmpty() ? List.of("PREPARE_HANDOFF", "CREATE_CHANGE_REQUEST_DRAFT") : List.of("RESOLVE_BLOCKERS"));
  }

  @Transactional(readOnly = true)
  public void requireReady(UUID tenantId, DraftQuote quote) {
    List<String> blocking = blockingIssues(tenantId, quote);
    if (!blocking.isEmpty()) {
      throw new QuoteHandoffViolation(String.join("; ", blocking));
    }
  }

  @Transactional(readOnly = true)
  public List<String> blockingIssues(UUID tenantId, DraftQuote quote) {
    List<String> blocking = new ArrayList<>();
    if (!tenantId.equals(quote.getTenantId())) {
      blocking.add("Tenant boundary validation failed");
      return blocking;
    }
    if (!"APPROVED".equals(quote.getStatus()) && !"APPROVED_INTERNAL".equals(quote.getStatus())) {
      blocking.add("Quote must be internally APPROVED before handoff preparation");
    }
    if (quote.getCustomerAccountId() == null) {
      blocking.add("Required customer/account reference is missing");
    }
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    if (lines.isEmpty()) {
      blocking.add("Quote has no line items");
    }
    if (issueRepository.countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(tenantId, quote.getId(), "OPEN") > 0) {
      blocking.add("Quote has unresolved blocking validation issues");
    }
    for (DraftQuoteLine line : lines) {
      if (line.getProductId() == null) {
        blocking.add("Quote line " + line.getLineNumber() + " has no resolved product");
      } else if (productRepository.findByIdAndTenantIdAndDeletedAtIsNull(line.getProductId(), tenantId).isEmpty()) {
        blocking.add("Quote line " + line.getLineNumber() + " resolved product is outside tenant scope");
      }
      if (line.getQuantity() == null || line.getQuantity().signum() <= 0) {
        blocking.add("Quote line " + line.getLineNumber() + " quantity is missing or invalid");
      }
      if (line.getUom() == null || line.getUom().isBlank() || "UNKNOWN".equals(line.getUom())) {
        blocking.add("Quote line " + line.getLineNumber() + " UOM is missing or unresolved");
      }
      if (line.getUnitPrice() == null) {
        blocking.add("Quote line " + line.getLineNumber() + " price is missing");
      }
      if (PENDING_SUBSTITUTE_STATES.contains(line.getSubstituteDecisionStatus())) {
        blocking.add("Quote line " + line.getLineNumber() + " has a pending substitute decision");
      }
      if (BLOCKED_SUBSTITUTE_STATES.contains(line.getSubstituteDecisionStatus())) {
        blocking.add("Quote line " + line.getLineNumber() + " selected substitute is blocked or not approved");
      }
      if (line.getSelectedSubstituteProductId() != null) {
        if (!"SUBSTITUTE_APPROVED".equals(line.getSubstituteDecisionStatus())) {
          blocking.add("Quote line " + line.getLineNumber() + " selected substitute is not approved");
        } else if (productRepository.findByIdAndTenantIdAndDeletedAtIsNull(line.getSelectedSubstituteProductId(), tenantId).isEmpty()) {
          blocking.add("Quote line " + line.getLineNumber() + " selected substitute is outside tenant scope");
        }
      }
    }
    return blocking;
  }

  public DraftQuote getQuote(UUID tenantId, UUID quoteId) {
    if (quoteId == null) {
      throw new IllegalArgumentException("quoteId is required");
    }
    return quoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
  }

  public static String metadata(UUID tenantId, DraftQuote quote, UUID snapshotId, UUID changeRequestId, String newStatus, String reason, String payloadHash, String idempotencyKey) {
    return "{\"tenantId\":\"" + tenantId + "\",\"quoteId\":\"" + quote.getId() + "\",\"snapshotId\":\"" + (snapshotId == null ? "" : snapshotId) + "\",\"changeRequestId\":\"" + (changeRequestId == null ? "" : changeRequestId) + "\",\"previousStatus\":\"" + quote.getStatus() + "\",\"newStatus\":\"" + newStatus + "\",\"reason\":\"" + escape(reason) + "\",\"payloadHash\":\"" + (payloadHash == null ? "" : payloadHash) + "\",\"idempotencyKey\":\"" + (idempotencyKey == null ? "" : idempotencyKey) + "\",\"externalExecution\":\"DISABLED\"}";
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
