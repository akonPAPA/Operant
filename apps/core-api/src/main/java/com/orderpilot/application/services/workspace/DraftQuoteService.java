package com.orderpilot.application.services.workspace;

import com.orderpilot.application.services.journey.OrderJourneyProjectionPublisher;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
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
public class DraftQuoteService {
  private final ValidationRunRepository runRepository; private final ExtractedLineItemRepository lineRepository; private final CustomerMatchResultRepository customerRepository; private final ProductMatchResultRepository productRepository; private final PriceCheckResultRepository priceRepository; private final UomNormalizationResultRepository uomRepository; private final MarginCheckResultRepository marginRepository; private final ValidationIssueRepository issueRepository; private final ApprovalRequirementRepository approvalRepository; private final DraftQuoteRepository quoteRepository; private final DraftQuoteLineRepository lineOutRepository; private final OperatorActionService actionService; private final OrderJourneyProjectionPublisher journeyProjectionPublisher; private final Clock clock;
  public DraftQuoteService(ValidationRunRepository runRepository, ExtractedLineItemRepository lineRepository, CustomerMatchResultRepository customerRepository, ProductMatchResultRepository productRepository, PriceCheckResultRepository priceRepository, UomNormalizationResultRepository uomRepository, MarginCheckResultRepository marginRepository, ValidationIssueRepository issueRepository, ApprovalRequirementRepository approvalRepository, DraftQuoteRepository quoteRepository, DraftQuoteLineRepository lineOutRepository, OperatorActionService actionService, OrderJourneyProjectionPublisher journeyProjectionPublisher, Clock clock){this.runRepository=runRepository;this.lineRepository=lineRepository;this.customerRepository=customerRepository;this.productRepository=productRepository;this.priceRepository=priceRepository;this.uomRepository=uomRepository;this.marginRepository=marginRepository;this.issueRepository=issueRepository;this.approvalRepository=approvalRepository;this.quoteRepository=quoteRepository;this.lineOutRepository=lineOutRepository;this.actionService=actionService;this.journeyProjectionPublisher=journeyProjectionPublisher;this.clock=clock;}
  @Transactional
  public DraftQuote createFromValidation(UUID validationRunId) {
    return createFromValidation(validationRunId, null, null, null);
  }
  @Transactional
  public DraftQuote createFromValidation(UUID validationRunId, UUID sourceExceptionCaseId) {
    return createFromValidation(validationRunId, sourceExceptionCaseId, null, null);
  }
  // OP-CAP-15B: optional selected-line subset (by extracted line item id; null = all eligible lines) and a
  // bounded operator note. Still builds from validated/normalized values only. Caller validates the selection.
  @Transactional
  public DraftQuote createFromValidation(UUID validationRunId, UUID sourceExceptionCaseId, Set<UUID> selectedLineIds, String operatorNote) {
    UUID tenantId = TenantContext.requireTenantId(); ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId).orElseThrow();
    String status = workflowStatus(tenantId, validationRunId); UUID customerId = customerRepository.findFirstByTenantIdAndValidationRunId(tenantId, validationRunId).map(CustomerMatchResult::getMatchedCustomerAccountId).orElse(null);
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-" + clock.instant().toEpochMilli(), customerId, run.getExtractionResultId(), validationRunId, sourceExceptionCaseId, status, null, null, clock.instant()));
    Map<UUID, ProductMatchResult> products = productRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(ProductMatchResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, PriceCheckResult> prices = priceRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(PriceCheckResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, UomNormalizationResult> uoms = uomRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(UomNormalizationResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    Map<UUID, MarginCheckResult> margins = marginRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream().collect(Collectors.toMap(MarginCheckResult::getExtractedLineItemId, Function.identity(), (a,b)->a));
    BigDecimal subtotal = BigDecimal.ZERO; String currency = null; int includedLines = 0;
    for (ExtractedLineItem line : lineRepository.findByTenantIdAndExtractionResultId(tenantId, run.getExtractionResultId())) {
      if (selectedLineIds != null && !selectedLineIds.contains(line.getId())) continue;
      BigDecimal qty = line.getNormalizedQuantity() == null ? BigDecimal.ONE : line.getNormalizedQuantity(); PriceCheckResult price = prices.get(line.getId()); ProductMatchResult product = products.get(line.getId()); UomNormalizationResult uom = uoms.get(line.getId()); MarginCheckResult margin = margins.get(line.getId());
      DraftQuoteLine ql = lineOutRepository.save(new DraftQuoteLine(tenantId, quote.getId(), line.getId(), product == null ? null : product.getMatchedProductId(), null, line.getLineNumber(), line.getRawDescription(), qty, uom == null || uom.getNormalizedUom() == null ? "EA" : uom.getNormalizedUom(), price == null ? null : price.getUnitPrice(), null, margin == null ? null : margin.getGrossMarginPercent(), "DRAFT", validationStatus(product, price), clock.instant()));
      if (ql.getLineTotal() != null) subtotal = subtotal.add(ql.getLineTotal()); if (currency == null && price != null) currency = price.getCurrency(); includedLines++;
    }
    quote.setTotals(subtotal, BigDecimal.ZERO, subtotal, null, clock.instant());
    quote.appendNote(operatorNote, clock.instant());
    actionService.record(null, "DRAFT_QUOTE", quote.getId(), "QUOTE_DRAFT_CREATED", "Internal draft quote created from validation run", "{\"validationRunId\":\"" + validationRunId + "\",\"selectedLineCount\":" + includedLines + ",\"notePresent\":" + (operatorNote != null && !operatorNote.isBlank()) + ",\"noteLength\":" + (operatorNote == null ? 0 : operatorNote.length()) + "}");
    DraftQuote saved = quoteRepository.save(quote);
    // OP-CAP-24: publish a durable, idempotent journey projection event inside this transaction. No projector
    // run, no journey mutation, no external write here — the explicit projector runner consumes it later.
    journeyProjectionPublisher.publishSourceEvent(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, saved.getId(), null);
    return saved;
  }
  @Transactional(readOnly = true) public List<DraftQuote> list(){return quoteRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
  @Transactional(readOnly = true) public DraftQuote get(UUID id){return quoteRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();}
  @Transactional(readOnly = true) public List<DraftQuoteLine> lines(UUID id){return lineOutRepository.findByTenantIdAndDraftQuoteId(TenantContext.requireTenantId(), id);}
  @Transactional public DraftQuote approve(UUID id){DraftQuote q=get(id); q.setStatus("APPROVED_INTERNAL", null, clock.instant()); actionService.record(null, "DRAFT_QUOTE", id, "APPROVAL_DECIDED", "Draft quote approved internally only", "{}"); return quoteRepository.save(q);}
  @Transactional public DraftQuote reject(UUID id){DraftQuote q=get(id); q.setStatus("REJECTED", null, clock.instant()); actionService.record(null, "DRAFT_QUOTE", id, "APPROVAL_DECIDED", "Draft quote rejected", "{}"); return quoteRepository.save(q);}
  @Transactional public DraftQuote cancel(UUID id){DraftQuote q=get(id); q.setStatus("CANCELLED", null, clock.instant()); actionService.record(null, "DRAFT_QUOTE", id, "OTHER", "Draft quote cancelled", "{}"); return quoteRepository.save(q);}
  private String workflowStatus(UUID tenantId, UUID runId){boolean approvals=!approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream().filter(a -> "OPEN".equals(a.getStatus())).toList().isEmpty(); boolean issues=!issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream().filter(i -> "OPEN".equals(i.getStatus())).toList().isEmpty(); return approvals?"WAITING_APPROVAL":issues?"NEEDS_REVIEW":"DRAFT";}
  private String validationStatus(ProductMatchResult p, PriceCheckResult price){return p==null||!"MATCHED".equals(p.getStatus())||price==null||!"PRICE_FOUND".equals(price.getStatus())?"NEEDS_REVIEW":"VALIDATED";}
}
