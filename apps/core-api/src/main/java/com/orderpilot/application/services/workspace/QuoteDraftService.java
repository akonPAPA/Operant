package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.DiscountRule;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteDraftService {
  private final CustomerResolutionService customerResolutionService;
  private final ProductResolutionService productResolutionService;
  private final QuoteInventoryValidationService inventoryValidationService;
  private final PricingService pricingService;
  private final QuoteMarginValidationService marginValidationService;
  private final SubstitutionService substitutionService;
  private final ApprovalPolicyService approvalPolicyService;
  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final QuoteApprovalRequestRepository approvalRepository;
  private final DiscountRuleRepository discountRuleRepository;
  private final LocationRepository locationRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public QuoteDraftService(
      CustomerResolutionService customerResolutionService,
      ProductResolutionService productResolutionService,
      QuoteInventoryValidationService inventoryValidationService,
      PricingService pricingService,
      QuoteMarginValidationService marginValidationService,
      SubstitutionService substitutionService,
      ApprovalPolicyService approvalPolicyService,
      DraftQuoteRepository quoteRepository,
      DraftQuoteLineRepository lineRepository,
      QuoteValidationIssueRepository issueRepository,
      QuoteApprovalRequestRepository approvalRepository,
      DiscountRuleRepository discountRuleRepository,
      LocationRepository locationRepository,
      AuditEventService auditEventService,
      Clock clock) {
    this.customerResolutionService = customerResolutionService;
    this.productResolutionService = productResolutionService;
    this.inventoryValidationService = inventoryValidationService;
    this.pricingService = pricingService;
    this.marginValidationService = marginValidationService;
    this.substitutionService = substitutionService;
    this.approvalPolicyService = approvalPolicyService;
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.approvalRepository = approvalRepository;
    this.discountRuleRepository = discountRuleRepository;
    this.locationRepository = locationRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public QuoteTransactionResponse createFromRfq(CreateDraftQuoteFromRfqCommand command) {
    CreateDraftQuoteFromRfqCommand request = command == null ? new CreateDraftQuoteFromRfqCommand(null, null, null, null, null, List.of(), null, null, null) : command;
    UUID tenantId = resolveTenant(request.tenantId());
    if (!isBlank(request.idempotencyKey())) {
      Optional<DraftQuote> existing = quoteRepository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey().trim());
      if (existing.isPresent()) {
        auditEventService.record("DRAFT_QUOTE_RFQ_IDEMPOTENT_REPLAY", "DRAFT_QUOTE", existing.get().getId().toString(), request.actorId(), "{\"idempotencyKey\":\"" + escape(request.idempotencyKey()) + "\",\"externalExecution\":\"DISABLED\"}");
        return response(tenantId, existing.get());
      }
    }

    Optional<CustomerAccount> customer = customerResolutionService.resolve(tenantId, request.customerExternalRef(), request.customerName());
    UUID auditCorrelationId = UUID.randomUUID();
    DraftQuote quote = new DraftQuote(tenantId, "DQ-" + clock.instant().toEpochMilli(), "RFQ", null, null, customer.map(CustomerAccount::getId).orElse(null), customer.map(CustomerAccount::getDisplayName).orElse(request.customerName()), "DRAFT", "PENDING_VALIDATION", false, customer.map(CustomerAccount::getDefaultCurrency).orElse("USD"), request.actorId(), auditCorrelationId, clock.instant());
    quote.setIdempotencyKey(request.idempotencyKey());
    quote = quoteRepository.save(quote);
    auditEventService.record("DRAFT_QUOTE_RFQ_CREATE_REQUESTED", "DRAFT_QUOTE", quote.getId().toString(), request.actorId(), "{\"auditCorrelationId\":\"" + auditCorrelationId + "\",\"externalExecution\":\"DISABLED\"}");

    List<PendingIssue> pendingIssues = new ArrayList<>();
    List<SubstituteCandidate> substituteCandidates = new ArrayList<>();
    if (customer.isEmpty()) {
      pendingIssues.add(new PendingIssue(null, "CUSTOMER_NOT_RESOLVED", "ERROR", true, "Customer could not be resolved by external reference, account code, or name", "{}"));
    }
    List<RequestedItem> items = request.requestedItems() == null ? List.of() : request.requestedItems();
    if (items.isEmpty()) {
      pendingIssues.add(new PendingIssue(null, "RFQ_LINE_MISSING", "ERROR", true, "RFQ must include at least one requested item", "{}"));
    }

    UUID locationId = resolveLocation(tenantId, request.requestedLocation(), customer.orElse(null));
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal discountTotal = BigDecimal.ZERO;
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal quoteMarginWeightedNumerator = BigDecimal.ZERO;
    BigDecimal quoteMarginWeightedDenominator = BigDecimal.ZERO;
    int lineNumber = 1;
    for (RequestedItem item : items) {
      List<PendingIssue> lineIssues = new ArrayList<>();
      BigDecimal quantity = safeQuantity(item.quantity());
      String uom = normalizeUom(item.uom());
      if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
        lineIssues.add(new PendingIssue(null, "INVALID_QUANTITY", "ERROR", true, "Quantity must be greater than zero", "{}"));
      }
      if ("UNKNOWN".equals(uom)) {
        lineIssues.add(new PendingIssue(null, "UOM_UNRECOGNIZED", "WARNING", false, "UOM could not be normalized", "{\"uom\":\"" + escape(item.uom()) + "\"}"));
      }

      ProductResolutionService.ProductResolution resolvedProduct = productResolutionService.resolve(tenantId, item.rawSkuOrAlias(), item.description(), customer.map(CustomerAccount::getId).orElse(null));
      Product product = resolvedProduct.product();
      if (product == null) {
        lineIssues.add(new PendingIssue(null, "PRODUCT_NOT_RESOLVED", "ERROR", true, "Product could not be resolved by exact SKU, alias, or OEM reference", "{\"rawSkuOrAlias\":\"" + escape(item.rawSkuOrAlias()) + "\"}"));
      }

      PriceRule price = null;
      QuoteInventoryValidationService.InventoryValidation inventory = null;
      BigDecimal unitPrice = null;
      BigDecimal grossLineTotal = null;
      BigDecimal netLineTotal = null;
      BigDecimal discountPercent = request.requestedDiscountPercent() == null ? BigDecimal.ZERO : request.requestedDiscountPercent();
      QuoteMarginValidationService.MarginValidation margin = new QuoteMarginValidationService.MarginValidation(null, false, false, "MARGIN_NOT_EVALUATED");

      if (product != null) {
        Optional<PriceRule> selectedPrice = pricingService.selectPrice(tenantId, product, customer.orElse(null), locationId, quantity, uom);
        if (selectedPrice.isPresent()) {
          price = selectedPrice.get();
          unitPrice = price.getUnitPrice();
          grossLineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
          netLineTotal = grossLineTotal.multiply(BigDecimal.ONE.subtract(discountPercent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP);
          discountTotal = discountTotal.add(grossLineTotal.subtract(netLineTotal));
          subtotal = subtotal.add(grossLineTotal);
          total = total.add(netLineTotal);
        } else {
          lineIssues.add(new PendingIssue(null, "PRICE_NOT_RESOLVED", "ERROR", true, "No active deterministic price rule matched customer, product, quantity, location, and UOM", "{}"));
        }

        inventory = inventoryValidationService.validate(tenantId, product.getId(), locationId, quantity);
        if (inventory.availableStock() == null) {
          lineIssues.add(new PendingIssue(null, "STOCK_NOT_EVALUATED", "WARNING", false, "No inventory snapshot exists for product and requested location", "{}"));
        } else if (!inventory.sufficient()) {
          lineIssues.add(new PendingIssue(null, "INSUFFICIENT_STOCK", "ERROR", true, "Available stock is below requested quantity", "{\"available\":\"" + inventory.availableStock() + "\"}"));
        }
        if (inventory != null && !inventory.sufficient()) {
          List<SubstituteCandidate> candidates = substitutionService.suggest(tenantId, null, product.getId(), item.rawSkuOrAlias(), item.description(), customer.map(CustomerAccount::getId).orElse(null), quantity);
          if (candidates.isEmpty()) {
            lineIssues.add(new PendingIssue(null, "NO_SAFE_SUBSTITUTE_FOUND", "ERROR", true, "No deterministic substitute candidate was available", "{}"));
          } else {
            lineIssues.add(new PendingIssue(null, "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE", "WARNING", true, "Requested product is out of stock and substitute candidates were found", "{\"candidateCount\":" + candidates.size() + "}"));
          }
          if (candidates.stream().anyMatch(SubstituteCandidate::blocked)) {
            lineIssues.add(new PendingIssue(null, "SUBSTITUTE_BLOCKED_FOR_CUSTOMER", "WARNING", true, "Blocked substitute exists and cannot be auto-approved", "{}"));
          }
          if (candidates.stream().anyMatch(SubstituteCandidate::requiresApproval)) {
            lineIssues.add(new PendingIssue(null, "SUBSTITUTE_REQUIRES_APPROVAL", "WARNING", true, "Risky substitute requires approval", "{}"));
          }
          substituteCandidates.addAll(candidates);
        }

        if (unitPrice != null) {
          checkDiscountRule(tenantId, customer.orElse(null), product, discountPercent, lineIssues);
          margin = marginValidationService.validate(tenantId, product, unitPrice, discountPercent);
          if (margin.marginPercent() != null && netLineTotal != null) {
            quoteMarginWeightedNumerator = quoteMarginWeightedNumerator.add(margin.marginPercent().multiply(netLineTotal));
            quoteMarginWeightedDenominator = quoteMarginWeightedDenominator.add(netLineTotal);
          }
          if (margin.violation()) {
            lineIssues.add(new PendingIssue(null, "MARGIN_BELOW_GUARDRAIL", "ERROR", true, "Requested discount drives margin below guardrail", "{\"marginPercent\":\"" + margin.marginPercent() + "\"}"));
          } else if (margin.approvalRequired()) {
            lineIssues.add(new PendingIssue(null, "MARGIN_APPROVAL_REQUIRED", "WARNING", true, "Requested discount requires margin approval", "{\"marginPercent\":\"" + margin.marginPercent() + "\"}"));
          }
        }
      }

      DraftQuoteLine line = lineRepository.save(new DraftQuoteLine(tenantId, quote.getId(), lineNumber++, firstNonBlank(item.description(), item.rawSkuOrAlias()), item.rawSkuOrAlias(), resolvedProduct.match().normalizedCode(), product == null ? null : product.getId(), product == null ? null : product.getName(), quantity, uom, request.requestedLocation(), unitPrice, inventory == null ? null : inventory.availableStock(), resolvedProduct.match().confidence(), "[]", lineIssues.stream().anyMatch(PendingIssue::blocking) ? "NEEDS_REVIEW" : "DRAFT", lineIssues.stream().anyMatch(PendingIssue::blocking) ? "NEEDS_REVIEW" : "VALIDATED", clock.instant()));
      line.applyTransactionPricing(discountPercent, netLineTotal, margin.marginPercent(), clock.instant());
      line = lineRepository.save(line);
      for (SubstituteCandidate candidate : substituteCandidates.stream().filter(candidate -> candidate.lineId() == null).toList()) {
        substituteCandidates.set(substituteCandidates.indexOf(candidate), new SubstituteCandidate(line.getId(), candidate.productId(), candidate.sku(), candidate.productName(), candidate.riskLevel(), candidate.reasonCode(), candidate.availableStock(), candidate.stockStatus(), candidate.requiresApproval(), candidate.blocked(), candidate.customerAccepted(), candidate.explanation()));
      }
      if (lineIssues.stream().anyMatch(issue -> Set.of("PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE", "SUBSTITUTE_REQUIRES_APPROVAL", "SUBSTITUTE_BLOCKED_FOR_CUSTOMER").contains(issue.code()))) {
        line.markSubstituteSuggested(substituteDecisionStatus(lineIssues), substituteDecisionReasonCode(lineIssues), clock.instant());
        line = lineRepository.save(line);
      }
      for (PendingIssue issue : lineIssues) {
        pendingIssues.add(issue.withLineId(line.getId()));
      }
    }

    for (PendingIssue issue : pendingIssues) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), issue.lineId(), issue.code(), issue.severity(), issue.blocking(), issue.message(), issue.detailsJson(), clock.instant()));
      if (requiresApproval(issue.code())) {
        approvalPolicyService.request(tenantId, quote.getId(), issue.lineId(), approvalType(issue.code()), issue.severity(), issue.code(), issue.message());
      }
    }

    boolean hasBlocking = pendingIssues.stream().anyMatch(PendingIssue::blocking);
    boolean approvalRequired = approvalRepository.countByTenantIdAndDraftQuoteIdAndStatus(tenantId, quote.getId(), "OPEN") > 0;
    String nextStatus = approvalRequired ? "PENDING_APPROVAL" : hasBlocking ? "NEEDS_REVIEW" : "DRAFT";
    BigDecimal quoteMargin = quoteMarginWeightedDenominator.compareTo(BigDecimal.ZERO) > 0
        ? quoteMarginWeightedNumerator.divide(quoteMarginWeightedDenominator, 2, RoundingMode.HALF_UP)
        : null;
    quote.setTotals(subtotal, discountTotal, total, quoteMargin, clock.instant());
    quote.markValidated(nextStatus, hasBlocking ? "NEEDS_REVIEW" : "VALIDATED", hasBlocking || approvalRequired, clock.instant());
    quote = quoteRepository.save(quote);
    auditEventService.record("DRAFT_QUOTE_CREATED", "DRAFT_QUOTE", quote.getId().toString(), request.actorId(), "{\"auditCorrelationId\":\"" + auditCorrelationId + "\",\"issueCount\":" + pendingIssues.size() + ",\"approvalRequired\":" + approvalRequired + ",\"externalExecution\":\"DISABLED\"}");
    return response(tenantId, quote);
  }

  @Transactional(readOnly = true)
  public QuoteTransactionResponse get(UUID quoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow();
    return response(tenantId, quote);
  }

  private QuoteTransactionResponse response(UUID tenantId, DraftQuote quote) {
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    List<QuoteValidationIssue> issues = issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    List<QuoteApprovalRequest> approvals = approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    ResolvedCustomer customer = quote.getCustomerAccountId() == null ? null : new ResolvedCustomer(quote.getCustomerAccountId(), null, null, quote.getCustomerDisplayName(), "RESOLVED");
    List<SubstituteCandidate> candidates = new ArrayList<>();
    for (DraftQuoteLine line : lines) {
      if (line.getProductId() != null && !"NO_SUBSTITUTE_REQUIRED".equals(line.getSubstituteDecisionStatus())) {
        candidates.addAll(substitutionService.suggest(tenantId, line.getId(), line.getProductId(), line.getRawSku(), line.getRawText(), quote.getCustomerAccountId(), line.getQuantity()));
      }
    }
    return QuoteTransactionResponse.from(quote, customer, lines, issues, candidates, approvals);
  }

  private UUID resolveTenant(UUID commandTenantId) {
    try {
      UUID contextTenantId = TenantContext.requireTenantId();
      if (commandTenantId != null && !commandTenantId.equals(contextTenantId)) {
        throw new IllegalArgumentException("Command tenantId does not match tenant context");
      }
      return contextTenantId;
    } catch (TenantContextMissingException ex) {
      if (commandTenantId == null) {
        throw ex;
      }
      TenantContext.setTenantId(commandTenantId);
      return commandTenantId;
    }
  }

  private UUID resolveLocation(UUID tenantId, String requestedLocation, CustomerAccount customer) {
    if (!isBlank(requestedLocation)) {
      return locationRepository.findByTenantIdAndCode(tenantId, requestedLocation.trim()).map(location -> location.getId()).orElse(null);
    }
    return customer == null ? null : customer.getDefaultLocationId();
  }

  private void checkDiscountRule(UUID tenantId, CustomerAccount customer, Product product, BigDecimal discountPercent, List<PendingIssue> issues) {
    BigDecimal discount = discountPercent == null ? BigDecimal.ZERO : discountPercent;
    Optional<DiscountRule> rule = discountRuleRepository.findByTenantIdAndActiveTrue(tenantId).stream()
        .filter(candidate -> candidate.getProductId() == null || candidate.getProductId().equals(product.getId()))
        .filter(candidate -> candidate.getCustomerAccountId() == null || (customer != null && candidate.getCustomerAccountId().equals(customer.getId())))
        .findFirst();
    if (rule.isEmpty()) {
      return;
    }
    if (discount.compareTo(rule.get().getMaxDiscountPercent()) > 0) {
      issues.add(new PendingIssue(null, "DISCOUNT_EXCEEDS_RULE", "ERROR", true, "Requested discount exceeds maximum configured discount", "{\"discountPercent\":\"" + discount + "\"}"));
    } else if (discount.compareTo(rule.get().getRequiresApprovalAbovePercent()) > 0) {
      issues.add(new PendingIssue(null, "DISCOUNT_APPROVAL_REQUIRED", "WARNING", true, "Requested discount requires approval", "{\"discountPercent\":\"" + discount + "\"}"));
    }
  }

  private static boolean requiresApproval(String code) {
    return Set.of("MARGIN_BELOW_GUARDRAIL", "MARGIN_APPROVAL_REQUIRED", "DISCOUNT_EXCEEDS_RULE", "DISCOUNT_APPROVAL_REQUIRED", "SUBSTITUTE_REQUIRES_APPROVAL", "SUBSTITUTE_BLOCKED_FOR_CUSTOMER").contains(code);
  }

  private static String approvalType(String code) {
    if (code.startsWith("MARGIN")) return "MARGIN_GUARDRAIL";
    if (code.startsWith("DISCOUNT")) return "DISCOUNT_POLICY";
    return "SUBSTITUTE_POLICY";
  }

  private static String substituteDecisionStatus(List<PendingIssue> issues) {
    if (issues.stream().anyMatch(issue -> "SUBSTITUTE_BLOCKED_FOR_CUSTOMER".equals(issue.code()))) return "SUBSTITUTE_BLOCKED";
    if (issues.stream().anyMatch(issue -> "SUBSTITUTE_REQUIRES_APPROVAL".equals(issue.code()))) return "SUBSTITUTE_APPROVAL_REQUIRED";
    return "SUBSTITUTE_SUGGESTED";
  }

  private static String substituteDecisionReasonCode(List<PendingIssue> issues) {
    return issues.stream().map(PendingIssue::code).filter(code -> code.startsWith("SUBSTITUTE") || code.startsWith("PRODUCT_OUT_OF_STOCK")).findFirst().orElse(null);
  }

  private static BigDecimal safeQuantity(BigDecimal quantity) {
    return quantity == null ? BigDecimal.ZERO : quantity;
  }

  private static String normalizeUom(String uom) {
    if (isBlank(uom)) return "UNKNOWN";
    String normalized = uom.trim().toLowerCase();
    if (Set.of("ea", "pcs", "pc", "unit", "units").contains(normalized)) return "EA";
    return "UNKNOWN";
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String value : values) {
      if (!isBlank(value)) return value;
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private record PendingIssue(UUID lineId, String code, String severity, boolean blocking, String message, String detailsJson) {
    PendingIssue withLineId(UUID nextLineId) {
      return new PendingIssue(nextLineId, code, severity, blocking, message, detailsJson);
    }
  }
}
