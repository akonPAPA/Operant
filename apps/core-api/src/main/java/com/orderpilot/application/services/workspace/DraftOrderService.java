package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftOrderService {
  private final ValidationRunRepository runRepository; private final ExtractedLineItemRepository lineRepository; private final CustomerMatchResultRepository customerRepository; private final ProductMatchResultRepository productRepository; private final PriceCheckResultRepository priceRepository; private final UomNormalizationResultRepository uomRepository; private final MarginCheckResultRepository marginRepository; private final InventoryCheckResultRepository inventoryRepository; private final ValidationIssueRepository issueRepository; private final ApprovalRequirementRepository approvalRepository; private final DraftOrderRepository orderRepository; private final DraftOrderLineRepository lineOutRepository; private final OperatorActionService actionService; private final Clock clock;
  public DraftOrderService(ValidationRunRepository runRepository, ExtractedLineItemRepository lineRepository, CustomerMatchResultRepository customerRepository, ProductMatchResultRepository productRepository, PriceCheckResultRepository priceRepository, UomNormalizationResultRepository uomRepository, MarginCheckResultRepository marginRepository, InventoryCheckResultRepository inventoryRepository, ValidationIssueRepository issueRepository, ApprovalRequirementRepository approvalRepository, DraftOrderRepository orderRepository, DraftOrderLineRepository lineOutRepository, OperatorActionService actionService, Clock clock){this.runRepository=runRepository;this.lineRepository=lineRepository;this.customerRepository=customerRepository;this.productRepository=productRepository;this.priceRepository=priceRepository;this.uomRepository=uomRepository;this.marginRepository=marginRepository;this.inventoryRepository=inventoryRepository;this.issueRepository=issueRepository;this.approvalRepository=approvalRepository;this.orderRepository=orderRepository;this.lineOutRepository=lineOutRepository;this.actionService=actionService;this.clock=clock;}
  @Transactional
  public DraftOrder createFromValidation(UUID validationRunId) {
    return createFromValidation(validationRunId, null, null, null);
  }
  @Transactional
  public DraftOrder createFromValidation(UUID validationRunId, UUID sourceExceptionCaseId) {
    return createFromValidation(validationRunId, sourceExceptionCaseId, null, null);
  }
  // OP-CAP-15B: optional selected-line subset (by extracted line item id; null = all eligible lines) and a
  // bounded operator note. Still builds from validated/normalized values only; no inventory reservation.
  @Transactional
  public DraftOrder createFromValidation(UUID validationRunId, UUID sourceExceptionCaseId, Set<UUID> selectedLineIds, String operatorNote) {
    UUID tenantId = TenantContext.requireTenantId(); ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId).orElseThrow(); String status = workflowStatus(tenantId, validationRunId);
    UUID customerId = customerRepository.findFirstByTenantIdAndValidationRunId(tenantId, validationRunId).map(CustomerMatchResult::getMatchedCustomerAccountId).orElse(null);
    DraftOrder order = orderRepository.save(new DraftOrder(tenantId, "DO-" + clock.instant().toEpochMilli(), customerId, run.getExtractionResultId(), validationRunId, sourceExceptionCaseId, status, null, null, clock.instant()));
    Map<UUID, ProductMatchResult> products = productRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(ProductMatchResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, PriceCheckResult> prices = priceRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(PriceCheckResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, UomNormalizationResult> uoms = uomRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(UomNormalizationResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, MarginCheckResult> margins = marginRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(MarginCheckResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, InventoryCheckResult> inventory = inventoryRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(InventoryCheckResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    BigDecimal subtotal = BigDecimal.ZERO; int includedLines = 0;
    for (ExtractedLineItem line : lineRepository.findByTenantIdAndExtractionResultId(tenantId, run.getExtractionResultId())) {
      if (selectedLineIds != null && !selectedLineIds.contains(line.getId())) continue;
      BigDecimal qty = line.getNormalizedQuantity() == null ? BigDecimal.ONE : line.getNormalizedQuantity(); PriceCheckResult price = prices.get(line.getId()); ProductMatchResult product = products.get(line.getId()); UomNormalizationResult uom = uoms.get(line.getId()); MarginCheckResult margin = margins.get(line.getId()); InventoryCheckResult inv = inventory.get(line.getId());
      String lineStatus = product == null || product.getMatchedProductId() == null || price == null || price.getUnitPrice() == null || inv == null || !"AVAILABLE".equals(inv.getStatus()) ? "NEEDS_REVIEW" : "DRAFT";
      DraftOrderLine ol = lineOutRepository.save(new DraftOrderLine(tenantId, order.getId(), line.getId(), product == null ? null : product.getMatchedProductId(), null, line.getLineNumber(), line.getRawDescription(), qty, uom == null || uom.getNormalizedUom() == null ? "EA" : uom.getNormalizedUom(), price == null ? null : price.getUnitPrice(), null, margin == null ? null : margin.getGrossMarginPercent(), null, lineStatus, "NEEDS_REVIEW".equals(lineStatus) ? "NEEDS_REVIEW" : "VALIDATED", clock.instant()));
      if (ol.getLineTotal() != null) subtotal = subtotal.add(ol.getLineTotal()); includedLines++;
    }
    order.setTotals(subtotal, BigDecimal.ZERO, subtotal, null, clock.instant());
    order.appendNote(operatorNote, clock.instant());
    actionService.record(null, "DRAFT_ORDER", order.getId(), "ORDER_DRAFT_CREATED", "Internal draft order created from validation run without inventory reservation", "{\"validationRunId\":\"" + validationRunId + "\",\"selectedLineCount\":" + includedLines + ",\"notePresent\":" + (operatorNote != null && !operatorNote.isBlank()) + ",\"noteLength\":" + (operatorNote == null ? 0 : operatorNote.length()) + "}");
    return orderRepository.save(order);
  }
  @Transactional(readOnly = true) public List<DraftOrder> list(){return orderRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
  @Transactional(readOnly = true) public DraftOrder get(UUID id){return orderRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();}
  @Transactional(readOnly = true) public List<DraftOrderLine> lines(UUID id){return lineOutRepository.findByTenantIdAndDraftOrderId(TenantContext.requireTenantId(), id);}
  @Transactional public DraftOrder approve(UUID id){DraftOrder o=get(id); o.setStatus("APPROVED_INTERNAL", null, clock.instant()); actionService.record(null, "DRAFT_ORDER", id, "APPROVAL_DECIDED", "Draft order approved internally only; no ERP or inventory write", "{}"); return orderRepository.save(o);}
  @Transactional public DraftOrder reject(UUID id){DraftOrder o=get(id); o.setStatus("REJECTED", null, clock.instant()); actionService.record(null, "DRAFT_ORDER", id, "APPROVAL_DECIDED", "Draft order rejected", "{}"); return orderRepository.save(o);}
  @Transactional public DraftOrder cancel(UUID id){DraftOrder o=get(id); o.setStatus("CANCELLED", null, clock.instant()); actionService.record(null, "DRAFT_ORDER", id, "OTHER", "Draft order cancelled", "{}"); return orderRepository.save(o);}
  private String workflowStatus(UUID tenantId, UUID runId){boolean approvals=!approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream().filter(a -> "OPEN".equals(a.getStatus())).toList().isEmpty(); boolean issues=!issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream().filter(i -> "OPEN".equals(i.getStatus())).toList().isEmpty(); return approvals?"WAITING_APPROVAL":issues?"NEEDS_REVIEW":"DRAFT";}
}
