package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage6Dtos.DraftLineCorrectionRequest;
import com.orderpilot.api.dto.Stage6Dtos.DraftOrderDetail;
import com.orderpilot.api.dto.Stage6Dtos.DraftOrderLineView;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteDetail;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteLineView;
import com.orderpilot.api.dto.Stage6Dtos.DraftReviewSummary;
import com.orderpilot.api.dto.Stage6Dtos.ProductPickerItem;
import com.orderpilot.api.dto.Stage6Dtos.ReviewActionRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderLine;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-09B Line-Level Operator Draft Review Workspace Foundation.
 *
 * Internal-only operator review over the existing DraftQuote/DraftOrder workspace model:
 * bounded detail, bounded line corrections, and a conservative mark-ready transition.
 * Tenant authority is always {@link TenantContext}. No final approval, no inventory/master-data
 * mutation, no connector/ERP/1C/accounting/external write, no outbox. AI output stays advisory/historical.
 */
@Service
public class DraftReviewService {
  private static final String EXTERNAL_EXECUTION = "DISABLED";
  private static final String REVIEW_STATUS = "NEEDS_REVIEW";
  private static final String READY_STATUS = "WAITING_APPROVAL"; // reuse existing enum as "ready for internal approval"
  private static final Set<String> TERMINAL_STATUSES = Set.of("APPROVED_INTERNAL", "APPROVED", "REJECTED", "CANCELLED", "REMOVED");
  private static final int MAX_UOM_LENGTH = 16;
  private static final int MAX_TEXT_LENGTH = 512;
  // OP-CAP-09D queue/picker bounds.
  private static final String NEXT_ACTION = "REVIEW_LINES";
  private static final Set<String> QUEUE_STATUS_ALLOWLIST = Set.of("DRAFT", "NEEDS_REVIEW", "WAITING_APPROVAL", "APPROVED_INTERNAL", "REJECTED", "CANCELLED");
  private static final int DEFAULT_QUEUE_LIMIT = 25;
  private static final int MAX_QUEUE_LIMIT = 100;
  private static final int DEFAULT_PRODUCT_LIMIT = 10;
  private static final int MAX_PRODUCT_LIMIT = 25;
  private static final String ACTIVE_STATUS = "ACTIVE";

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository quoteLineRepository;
  private final DraftOrderRepository orderRepository;
  private final DraftOrderLineRepository orderLineRepository;
  private final ProductRepository productRepository;
  private final OperatorActionService actionService;
  private final Clock clock;

  public DraftReviewService(DraftQuoteRepository quoteRepository, DraftQuoteLineRepository quoteLineRepository, DraftOrderRepository orderRepository, DraftOrderLineRepository orderLineRepository, ProductRepository productRepository, OperatorActionService actionService, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.quoteLineRepository = quoteLineRepository;
    this.orderRepository = orderRepository;
    this.orderLineRepository = orderLineRepository;
    this.productRepository = productRepository;
    this.actionService = actionService;
    this.clock = clock;
  }

  // --- Draft quote review ---

  @Transactional(readOnly = true)
  public DraftQuoteDetail quoteDetail(UUID draftQuoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quoteRepository.findByIdAndTenantId(draftQuoteId, tenantId).orElseThrow();
    return quoteDetail(quote, tenantId);
  }

  @Transactional
  public DraftQuoteDetail correctQuoteLine(UUID draftQuoteId, UUID lineId, DraftLineCorrectionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quoteRepository.findByIdAndTenantId(draftQuoteId, tenantId).orElseThrow();
    ensureCorrectable(quote.getStatus());
    validate(request, tenantId);
    DraftQuoteLine line = quoteLineRepository.findByIdAndTenantId(lineId, tenantId).orElseThrow();
    if (!line.getDraftQuoteId().equals(draftQuoteId)) {
      throw new IllegalArgumentException("Line does not belong to draft quote");
    }
    ensureLineCorrectable(line.getStatus());
    String productName = productName(request.productId(), tenantId);
    line.applyOperatorCorrection(request.quantity(), request.uom(), request.description(), request.unitPrice(), request.productId(), productName, clock.instant());
    quoteLineRepository.save(line);
    List<DraftQuoteLine> lines = quoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, draftQuoteId);
    BigDecimal subtotal = sumQuote(lines);
    quote.setTotals(subtotal, nullSafe(quote.getDiscountAmount()), subtotal, quote.getMarginPercent(), clock.instant());
    quote.setStatus(REVIEW_STATUS, request.actorUserId(), clock.instant());
    quoteRepository.save(quote);
    actionService.record(request.actorUserId(), "DRAFT_QUOTE", draftQuoteId, "DRAFT_QUOTE_LINE_CORRECTED", "Operator corrected draft quote line", correctionMetadata(lineId, request));
    return quoteDetail(quote, tenantId);
  }

  @Transactional
  public DraftQuoteDetail markQuoteReady(UUID draftQuoteId, ReviewActionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = request == null ? null : request.actorUserId();
    DraftQuote quote = quoteRepository.findByIdAndTenantId(draftQuoteId, tenantId).orElseThrow();
    String from = quote.getStatus();
    if (READY_STATUS.equals(from)) {
      return quoteDetail(quote, tenantId); // idempotent: already ready, no duplicate transition/audit
    }
    ensureTransitionable(from);
    quote.setStatus(READY_STATUS, actorId, clock.instant());
    quoteRepository.save(quote);
    actionService.record(actorId, "DRAFT_QUOTE", draftQuoteId, "DRAFT_QUOTE_MARKED_READY", "Draft quote marked ready for internal approval", transitionMetadata(from, READY_STATUS, request));
    return quoteDetail(quote, tenantId);
  }

  // --- Draft order review ---

  @Transactional(readOnly = true)
  public DraftOrderDetail orderDetail(UUID draftOrderId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftOrder order = orderRepository.findByIdAndTenantId(draftOrderId, tenantId).orElseThrow();
    return orderDetail(order, tenantId);
  }

  @Transactional
  public DraftOrderDetail correctOrderLine(UUID draftOrderId, UUID lineId, DraftLineCorrectionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftOrder order = orderRepository.findByIdAndTenantId(draftOrderId, tenantId).orElseThrow();
    ensureCorrectable(order.getStatus());
    validate(request, tenantId);
    DraftOrderLine line = orderLineRepository.findByIdAndTenantId(lineId, tenantId).orElseThrow();
    if (!line.getDraftOrderId().equals(draftOrderId)) {
      throw new IllegalArgumentException("Line does not belong to draft order");
    }
    ensureLineCorrectable(line.getStatus());
    line.applyOperatorCorrection(request.quantity(), request.uom(), request.description(), request.unitPrice(), request.productId(), clock.instant());
    orderLineRepository.save(line);
    List<DraftOrderLine> lines = orderLineRepository.findByTenantIdAndDraftOrderId(tenantId, draftOrderId);
    BigDecimal subtotal = sumOrder(lines);
    order.setTotals(subtotal, nullSafe(order.getDiscountAmount()), subtotal, order.getMarginPercent(), clock.instant());
    order.setStatus(REVIEW_STATUS, request.actorUserId(), clock.instant());
    orderRepository.save(order);
    actionService.record(request.actorUserId(), "DRAFT_ORDER", draftOrderId, "DRAFT_ORDER_LINE_CORRECTED", "Operator corrected draft order line", correctionMetadata(lineId, request));
    return orderDetail(order, tenantId);
  }

  @Transactional
  public DraftOrderDetail markOrderReady(UUID draftOrderId, ReviewActionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = request == null ? null : request.actorUserId();
    DraftOrder order = orderRepository.findByIdAndTenantId(draftOrderId, tenantId).orElseThrow();
    String from = order.getStatus();
    if (READY_STATUS.equals(from)) {
      return orderDetail(order, tenantId);
    }
    ensureTransitionable(from);
    order.setStatus(READY_STATUS, actorId, clock.instant());
    orderRepository.save(order);
    actionService.record(actorId, "DRAFT_ORDER", draftOrderId, "DRAFT_ORDER_MARKED_READY", "Draft order marked ready for internal approval", transitionMetadata(from, READY_STATUS, request));
    return orderDetail(order, tenantId);
  }

  // --- OP-CAP-09D: bounded review queues + read-only product picker ---

  @Transactional(readOnly = true)
  public List<DraftReviewSummary> quoteReviewQueue(String status, UUID sourceReviewCaseId, String customerRef, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    List<DraftQuote> quotes = quoteRepository.searchReviewQueue(tenantId, normalizeStatusFilter(status), sourceReviewCaseId, blankToNull(customerRef), PageRequest.of(0, clampQueueLimit(limit)));
    Map<UUID, Long> counts = quoteLineCounts(tenantId, quotes.stream().map(DraftQuote::getId).toList());
    return quotes.stream()
        .map(q -> new DraftReviewSummary(q.getId(), "QUOTE", q.getStatus(), q.getSourceExceptionCaseId(), q.getSourceValidationRunId(), q.getCustomerAccountId(), q.getCustomerDisplayName(), counts.getOrDefault(q.getId(), 0L).intValue(), q.getSubtotalAmount(), q.getTotalAmount(), q.getCurrency(), q.getCreatedAt(), q.getUpdatedAt(), EXTERNAL_EXECUTION, NEXT_ACTION))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<DraftReviewSummary> orderReviewQueue(String status, UUID sourceReviewCaseId, String customerRef, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    // Draft orders have no denormalized customer name column; customerRef name search is quote-only (documented).
    List<DraftOrder> orders = orderRepository.searchReviewQueue(tenantId, normalizeStatusFilter(status), sourceReviewCaseId, PageRequest.of(0, clampQueueLimit(limit)));
    Map<UUID, Long> counts = orderLineCounts(tenantId, orders.stream().map(DraftOrder::getId).toList());
    return orders.stream()
        .map(o -> new DraftReviewSummary(o.getId(), "ORDER", o.getStatus(), o.getSourceExceptionCaseId(), o.getSourceValidationRunId(), o.getCustomerAccountId(), null, counts.getOrDefault(o.getId(), 0L).intValue(), o.getSubtotalAmount(), o.getTotalAmount(), o.getCurrency(), o.getCreatedAt(), o.getUpdatedAt(), EXTERNAL_EXECUTION, NEXT_ACTION))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ProductPickerItem> searchProducts(String q, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    String term = blankToNull(q);
    if (term == null) {
      return List.of(); // require a search term; never run an unbounded product scan
    }
    int clamped = clampProductLimit(limit);
    return productRepository
        .findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(tenantId, term, tenantId, term)
        .stream()
        .filter(product -> ACTIVE_STATUS.equals(product.getStatus()))
        .limit(clamped)
        .map(product -> new ProductPickerItem(product.getId(), product.getSku(), product.getName(), product.getNormalizedSku(), product.getStatus()))
        .toList();
  }

  private Map<UUID, Long> quoteLineCounts(UUID tenantId, List<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : quoteLineRepository.countByDraftQuoteIds(tenantId, ids)) {
      counts.put((UUID) row[0], (Long) row[1]);
    }
    return counts;
  }

  private Map<UUID, Long> orderLineCounts(UUID tenantId, List<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : orderLineRepository.countByDraftOrderIds(tenantId, ids)) {
      counts.put((UUID) row[0], (Long) row[1]);
    }
    return counts;
  }

  private String normalizeStatusFilter(String status) {
    String value = blankToNull(status);
    if (value == null) {
      return null;
    }
    String upper = value.toUpperCase();
    if (!QUEUE_STATUS_ALLOWLIST.contains(upper)) {
      throw new IllegalArgumentException("Unsupported draft status filter: " + status);
    }
    return upper;
  }

  private int clampQueueLimit(int limit) {
    return Math.max(1, Math.min(limit <= 0 ? DEFAULT_QUEUE_LIMIT : limit, MAX_QUEUE_LIMIT));
  }

  private int clampProductLimit(int limit) {
    return Math.max(1, Math.min(limit <= 0 ? DEFAULT_PRODUCT_LIMIT : limit, MAX_PRODUCT_LIMIT));
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // --- validation / guards ---

  private void validate(DraftLineCorrectionRequest request, UUID tenantId) {
    if (request == null || !request.hasAnyField()) {
      throw new IllegalArgumentException("At least one correctable field must be provided");
    }
    if (request.quantity() != null && request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Quantity must be positive");
    }
    if (request.unitPrice() != null && request.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("Unit price must not be negative");
    }
    if (request.uom() != null) {
      String uom = request.uom().trim();
      if (uom.isEmpty() || uom.length() > MAX_UOM_LENGTH) {
        throw new IllegalArgumentException("UOM must be 1-" + MAX_UOM_LENGTH + " characters");
      }
    }
    if (request.description() != null && request.description().length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("Description must be at most " + MAX_TEXT_LENGTH + " characters");
    }
    if (request.correctionReason() != null && request.correctionReason().length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("Correction reason must be at most " + MAX_TEXT_LENGTH + " characters");
    }
    if (request.productId() != null && productRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.productId(), tenantId).isEmpty()) {
      throw new IllegalArgumentException("Product is not available for this tenant");
    }
  }

  private void ensureCorrectable(String draftStatus) {
    if (TERMINAL_STATUSES.contains(draftStatus)) {
      throw new IllegalArgumentException("Draft in status " + draftStatus + " is locked and cannot be corrected");
    }
  }

  private void ensureLineCorrectable(String lineStatus) {
    if (TERMINAL_STATUSES.contains(lineStatus)) {
      throw new IllegalArgumentException("Line in status " + lineStatus + " is locked and cannot be corrected");
    }
  }

  private void ensureTransitionable(String draftStatus) {
    if (TERMINAL_STATUSES.contains(draftStatus)) {
      throw new IllegalArgumentException("Draft in status " + draftStatus + " cannot be marked ready");
    }
  }

  private String productName(UUID productId, UUID tenantId) {
    if (productId == null) {
      return null;
    }
    return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId).map(p -> p.getName()).orElse(null);
  }

  // --- mapping ---

  private DraftQuoteDetail quoteDetail(DraftQuote quote, UUID tenantId) {
    List<DraftQuoteLineView> lines = quoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId()).stream()
        .map(this::quoteLineView)
        .toList();
    return new DraftQuoteDetail(quote.getId(), quote.getSourceExceptionCaseId(), quote.getSourceValidationRunId(), quote.getCustomerAccountId(), quote.getCustomerDisplayName(), quote.getStatus(), quote.getValidationStatus(), quote.isRequiresHumanReview(), quote.getCurrency(), quote.getSubtotalAmount(), quote.getDiscountAmount(), quote.getTotalAmount(), quote.getMarginPercent(), lines.size(), lines, EXTERNAL_EXECUTION, quote.getCreatedAt());
  }

  private DraftQuoteLineView quoteLineView(DraftQuoteLine line) {
    return new DraftQuoteLineView(line.getId(), line.getLineNumber(), line.getProductId(), line.getRawSku(), line.getNormalizedSku(), line.getProductName(), line.getDescription(), line.getQuantity(), line.getUom(), line.getUnitPrice(), line.getDiscountPercent(), line.getLineTotal(), line.getMarginPercent(), line.getStatus(), line.getValidationStatus());
  }

  private DraftOrderDetail orderDetail(DraftOrder order, UUID tenantId) {
    List<DraftOrderLineView> lines = orderLineRepository.findByTenantIdAndDraftOrderId(tenantId, order.getId()).stream()
        .map(this::orderLineView)
        .toList();
    return new DraftOrderDetail(order.getId(), order.getSourceExceptionCaseId(), order.getSourceValidationRunId(), order.getCustomerAccountId(), order.getStatus(), order.getCurrency(), order.getSubtotalAmount(), order.getDiscountAmount(), order.getTotalAmount(), order.getMarginPercent(), lines.size(), lines, EXTERNAL_EXECUTION, order.getCreatedAt());
  }

  private DraftOrderLineView orderLineView(DraftOrderLine line) {
    return new DraftOrderLineView(line.getId(), line.getLineNumber(), line.getProductId(), line.getDescription(), line.getQuantity(), line.getUom(), line.getUnitPrice(), line.getDiscountPercent(), line.getLineTotal(), line.getMarginPercent(), line.getStatus(), line.getValidationStatus());
  }

  private BigDecimal sumQuote(List<DraftQuoteLine> lines) {
    return lines.stream().map(DraftQuoteLine::getLineTotal).filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal sumOrder(List<DraftOrderLine> lines) {
    return lines.stream().map(DraftOrderLine::getLineTotal).filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  // Bounded audit metadata: changed field NAMES and reason only — never raw before/after sensitive payloads or AI JSON.
  private String correctionMetadata(UUID lineId, DraftLineCorrectionRequest request) {
    StringBuilder fields = new StringBuilder();
    appendField(fields, "quantity", request.quantity() != null);
    appendField(fields, "uom", request.uom() != null);
    appendField(fields, "description", request.description() != null);
    appendField(fields, "unitPrice", request.unitPrice() != null);
    appendField(fields, "productId", request.productId() != null);
    return "{\"lineId\":\"" + lineId + "\",\"changedFields\":[" + fields + "],\"reason\":\"" + escape(request.correctionReason()) + "\",\"externalExecution\":\"" + EXTERNAL_EXECUTION + "\"}";
  }

  private String transitionMetadata(String from, String to, ReviewActionRequest request) {
    String reason = request == null ? null : request.reason();
    return "{\"fromStatus\":\"" + escape(from) + "\",\"toStatus\":\"" + escape(to) + "\",\"reason\":\"" + escape(reason) + "\",\"externalExecution\":\"" + EXTERNAL_EXECUTION + "\"}";
  }

  private void appendField(StringBuilder builder, String name, boolean present) {
    if (present) {
      if (builder.length() > 0) {
        builder.append(",");
      }
      builder.append("\"").append(name).append("\"");
    }
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
