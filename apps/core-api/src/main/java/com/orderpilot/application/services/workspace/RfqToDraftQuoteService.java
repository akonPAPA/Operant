package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCatalogMatchingService.ProductCatalogMatchResult;
import com.orderpilot.application.services.ProductCatalogMatchingService.ProductMatchType;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.application.services.ProductSubstitutionService.SubstituteCandidate;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.security.policy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RfqToDraftQuoteService {
  private static final Pattern QUANTITY_PATTERN = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(pcs|pc|units|unit|шт|ea)\\b");

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final CustomerAccountRepository customerRepository;
  private final ProductRepository productRepository;
  private final ProductCatalogMatchingService productMatchingService;
  private final ProductSubstitutionService productSubstitutionService;
  private final InventorySnapshotRepository inventoryRepository;
  private final PriceRuleRepository priceRuleRepository;
  private final MarginRuleRepository marginRuleRepository;
  private final ChannelMessageRepository channelMessageRepository;
  private final InboundDocumentRepository inboundDocumentRepository;
  private final ConnectorCommandRepository connectorCommandRepository;
  private final ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  private final CompensationPlanRepository compensationPlanRepository;
  private final TenantPolicyService tenantPolicyService;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public RfqToDraftQuoteService(
      DraftQuoteRepository quoteRepository,
      DraftQuoteLineRepository lineRepository,
      QuoteValidationIssueRepository issueRepository,
      CustomerAccountRepository customerRepository,
      ProductRepository productRepository,
      ProductCatalogMatchingService productMatchingService,
      ProductSubstitutionService productSubstitutionService,
      InventorySnapshotRepository inventoryRepository,
      PriceRuleRepository priceRuleRepository,
      MarginRuleRepository marginRuleRepository,
      ChannelMessageRepository channelMessageRepository,
      InboundDocumentRepository inboundDocumentRepository,
      ConnectorCommandRepository connectorCommandRepository,
      ConnectorSandboxExecutionRepository sandboxExecutionRepository,
      CompensationPlanRepository compensationPlanRepository,
      TenantPolicyService tenantPolicyService,
      AuditEventService auditEventService,
      Clock clock) {
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.customerRepository = customerRepository;
    this.productRepository = productRepository;
    this.productMatchingService = productMatchingService;
    this.productSubstitutionService = productSubstitutionService;
    this.inventoryRepository = inventoryRepository;
    this.priceRuleRepository = priceRuleRepository;
    this.marginRuleRepository = marginRuleRepository;
    this.channelMessageRepository = channelMessageRepository;
    this.inboundDocumentRepository = inboundDocumentRepository;
    this.connectorCommandRepository = connectorCommandRepository;
    this.sandboxExecutionRepository = sandboxExecutionRepository;
    this.compensationPlanRepository = compensationPlanRepository;
    this.tenantPolicyService = tenantPolicyService;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public DraftQuoteResponse createFromRfq(CreateDraftQuoteFromRfqRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    CreateDraftQuoteFromRfqRequest command = request == null ? new CreateDraftQuoteFromRfqRequest(null, null, null, null, null, null, null, List.of()) : request;
    ActorRole role = parseRole(command.actorRole());
    TenantPolicyDecision decision = tenantPolicyService.evaluate(TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(tenantId)
        .actorId(command.actorId())
        .actorRoles(Set.of(role))
        .action(TenantPolicyAction.CREATE_DRAFT_QUOTE)
        .resourceType(ResourceType.QUOTE)
        .systemActor(false)
        .build());
    if (!decision.allowed()) {
      auditEventService.record("DRAFT_QUOTE_CREATION_DENIED_BY_POLICY", "DRAFT_QUOTE", "not-created", command.actorId(), "{\"reasonCode\":\"" + escape(decision.reasonCode()) + "\"}");
      throw new TenantPolicyException(decision.message());
    }

    validateSource(tenantId, command.sourceMessageId(), command.sourceDocumentId());
    auditEventService.record("DRAFT_QUOTE_CREATION_REQUESTED", "DRAFT_QUOTE", "pending", command.actorId(), "{\"sourceType\":\"" + escape(sourceType(command.sourceType())) + "\"}");

    CustomerAccount customer = resolveCustomer(tenantId, command.customerHint()).orElse(null);
    String customerDisplay = customer == null ? blankToNull(command.customerHint()) : customer.getDisplayName();
    UUID correlationId = UUID.randomUUID();
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-" + clock.instant().toEpochMilli(), sourceType(command.sourceType()), command.sourceMessageId(), command.sourceDocumentId(), customer == null ? null : customer.getId(), customerDisplay, "NEEDS_REVIEW", "PENDING_VALIDATION", true, customer == null ? "USD" : customer.getDefaultCurrency(), command.actorId(), correlationId, clock.instant()));

    List<PendingIssue> pendingIssues = new ArrayList<>();
    if (customer == null) {
      pendingIssues.add(new PendingIssue(null, "CUSTOMER_NOT_RESOLVED", "WARNING", true, "Customer could not be resolved from RFQ input", "{\"customerHint\":\"" + escape(command.customerHint()) + "\"}"));
    }

    List<RfqLineInput> lines = normalizedLines(command);
    if (lines.isEmpty()) {
      pendingIssues.add(new PendingIssue(null, "RFQ_LINE_MISSING", "ERROR", true, "RFQ must include at least one line item or parseable message text", "{}"));
    }

    BigDecimal subtotal = BigDecimal.ZERO;
    String currency = quote.getCurrency();
    int lineNumber = 1;
    for (RfqLineInput input : lines) {
      LineResolution resolved = resolveLine(tenantId, customer, input);
      DraftQuoteLine line = lineRepository.save(new DraftQuoteLine(tenantId, quote.getId(), lineNumber++, firstNonBlank(input.rawText(), input.rawSku()), input.rawSku(), resolved.normalizedCode(), resolved.productId(), resolved.productName(), safeQuantity(input.quantity()), normalizeUom(input.uom()), input.requestedLocation(), resolved.unitPrice(), resolved.availableStock(), resolved.confidence(), issueCodesJson(resolved.issues()), resolved.issues().isEmpty() ? "DRAFT" : "NEEDS_REVIEW", resolved.blocking() ? "NEEDS_REVIEW" : "VALIDATED", clock.instant()));
      String substituteDecision = substituteDecisionStatus(resolved.issues());
      if (!"NO_SUBSTITUTE_REQUIRED".equals(substituteDecision)) {
        line.markSubstituteSuggested(substituteDecision, substituteDecisionReasonCode(resolved.issues()), clock.instant());
        line = lineRepository.save(line);
      }
      for (PendingIssue issue : resolved.issues()) {
        pendingIssues.add(issue.withLineId(line.getId()));
      }
      if (line.getLineTotal() != null) {
        subtotal = subtotal.add(line.getLineTotal());
      }
      if (resolved.currency() != null) {
        currency = resolved.currency();
      }
    }

    int blocking = 0;
    for (PendingIssue pending : pendingIssues) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), pending.lineId(), pending.code(), pending.severity(), pending.blocking(), pending.message(), pending.detailsJson(), clock.instant()));
      if (pending.blocking()) {
        blocking++;
      }
    }
    boolean substitutionReview = pendingIssues.stream().anyMatch(issue -> Set.of("PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE", "SUBSTITUTE_REQUIRES_APPROVAL", "SUBSTITUTE_BLOCKED_FOR_CUSTOMER", "COMPATIBILITY_UNVERIFIED").contains(issue.code()));
    String nextStatus = substitutionReview ? "SUBSTITUTION_REVIEW" : blocking == 0 ? "READY_FOR_APPROVAL" : "NEEDS_REVIEW";
    String validationStatus = blocking == 0 ? "VALIDATED" : "NEEDS_REVIEW";
    quote.setTotals(subtotal, BigDecimal.ZERO, subtotal, null, clock.instant());
    quote.markValidated(nextStatus, validationStatus, true, clock.instant());
    quote = quoteRepository.save(quote);
    auditEventService.record("DRAFT_QUOTE_CREATED", "DRAFT_QUOTE", quote.getId().toString(), command.actorId(), "{\"sourceType\":\"" + escape(quote.getSourceType()) + "\",\"externalExecution\":\"DISABLED\"}");
    auditEventService.record("DRAFT_QUOTE_VALIDATION_COMPLETED", "DRAFT_QUOTE", quote.getId().toString(), command.actorId(), "{\"issueCount\":" + pendingIssues.size() + ",\"blockingIssueCount\":" + blocking + ",\"status\":\"" + nextStatus + "\"}");
    return response(quote);
  }

  @Transactional(readOnly = true)
  public DraftQuoteResponse get(UUID id) {
    DraftQuote quote = quoteRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Draft quote not found: " + id));
    return response(quote);
  }

  @Transactional(readOnly = true)
  public List<DraftQuoteResponse> list(String status, String sourceType) {
    UUID tenantId = TenantContext.requireTenantId();
    List<DraftQuote> quotes;
    if (!isBlank(status) && !isBlank(sourceType)) {
      quotes = quoteRepository.findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(tenantId, status, sourceType);
    } else if (!isBlank(status)) {
      quotes = quoteRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
    } else if (!isBlank(sourceType)) {
      quotes = quoteRepository.findByTenantIdAndSourceTypeOrderByCreatedAtDesc(tenantId, sourceType);
    } else {
      quotes = quoteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }
    return quotes.stream().map(this::response).toList();
  }

  private DraftQuoteResponse response(DraftQuote quote) {
    UUID tenantId = TenantContext.requireTenantId();
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    Map<UUID, List<SubstituteCandidateResponse>> candidates = new HashMap<>();
    for (DraftQuoteLine line : lines) {
      candidates.put(line.getId(), substituteResponses(productSubstitutionService.suggest(tenantId, line.getProductId(), line.getRawSku(), line.getRawText(), quote.getCustomerAccountId(), line.getQuantity())));
    }
    return DraftQuoteResponse.from(quote, lines, issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId()), candidates);
  }

  private void validateSource(UUID tenantId, UUID messageId, UUID documentId) {
    if (messageId != null && channelMessageRepository.findByIdAndTenantId(messageId, tenantId).isEmpty()) {
      throw new NotFoundException("Source channel message not found: " + messageId);
    }
    if (documentId != null && inboundDocumentRepository.findByIdAndTenantId(documentId, tenantId).isEmpty()) {
      throw new NotFoundException("Source inbound document not found: " + documentId);
    }
  }

  private Optional<CustomerAccount> resolveCustomer(UUID tenantId, String customerHint) {
    if (isBlank(customerHint)) {
      return Optional.empty();
    }
    return customerRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, customerHint.trim());
  }

  private LineResolution resolveLine(UUID tenantId, CustomerAccount customer, RfqLineInput input) {
    List<PendingIssue> issues = new ArrayList<>();
    BigDecimal quantity = safeQuantity(input.quantity());
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      issues.add(new PendingIssue(null, "INVALID_QUANTITY", "ERROR", true, "Quantity must be greater than zero", "{}"));
    }
    String normalizedUom = normalizeUom(input.uom());
    if ("UNKNOWN".equals(normalizedUom)) {
      issues.add(new PendingIssue(null, "UOM_UNRECOGNIZED", "WARNING", false, "UOM could not be normalized", "{\"uom\":\"" + escape(input.uom()) + "\"}"));
    }
    ProductCatalogMatchResult match = productMatchingService.match(tenantId, firstNonBlank(input.rawSku(), input.rawText()), input.rawText(), customer == null ? null : customer.getId());
    Optional<Product> product = match.productId() == null ? Optional.empty() : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(match.productId(), tenantId);
    if (match.matchType() == ProductMatchType.AMBIGUOUS) {
      issues.add(new PendingIssue(null, "PRODUCT_MATCH_AMBIGUOUS", "ERROR", true, "Product lookup returned multiple active tenant candidates", "{\"normalizedCode\":\"" + escape(match.normalizedCode()) + "\"}"));
    } else if (product.isEmpty()) {
      issues.add(new PendingIssue(null, "PRODUCT_NOT_RESOLVED", "ERROR", true, "Product could not be resolved from RFQ line", "{\"rawSku\":\"" + escape(input.rawSku()) + "\",\"normalizedCode\":\"" + escape(match.normalizedCode()) + "\"}"));
    } else if (match.matchType() == ProductMatchType.NAME_TEXT_WEAK) {
      issues.add(new PendingIssue(null, "PRODUCT_MATCH_LOW_CONFIDENCE", "WARNING", true, "Product was matched with low confidence and requires review", "{\"normalizedCode\":\"" + escape(match.normalizedCode()) + "\"}"));
    }
    BigDecimal unitPrice = null;
    String currency = null;
    BigDecimal available = null;
    if (product.isPresent()) {
      Product p = product.get();
      Optional<PriceRule> price = priceRuleRepository.findByTenantIdOrderByPriorityAsc(tenantId).stream()
          .filter(rule -> rule.isActive() && rule.getProductId().equals(p.getId()))
          .filter(rule -> rule.getCustomerAccountId() == null || (customer != null && rule.getCustomerAccountId().equals(customer.getId())))
          .filter(rule -> rule.getUom().equalsIgnoreCase(normalizedUom))
          .filter(rule -> rule.getMinQuantity().compareTo(quantity) <= 0)
          .findFirst();
      if (price.isPresent()) {
        unitPrice = price.get().getUnitPrice();
        currency = price.get().getCurrency();
      } else {
        issues.add(new PendingIssue(null, "PRICE_NOT_RESOLVED", "ERROR", true, "Price could not be resolved for RFQ line", "{}"));
      }
      List<InventorySnapshot> inventory = inventoryRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, p.getId());
      if (inventory.isEmpty()) {
        issues.add(new PendingIssue(null, "STOCK_NOT_EVALUATED", "WARNING", false, "No inventory snapshot exists for product", "{}"));
      } else {
        available = inventory.get(0).getQuantityAvailable();
        if (available.compareTo(quantity) < 0) {
          issues.add(new PendingIssue(null, "INSUFFICIENT_STOCK", "ERROR", true, "Available stock is below requested quantity", "{\"available\":\"" + available + "\"}"));
          List<SubstituteCandidate> substitutes = productSubstitutionService.suggest(tenantId, p.getId(), input.rawSku(), input.rawText(), customer == null ? null : customer.getId(), quantity);
          if (hasSafeAvailableCandidate(substitutes)) {
            issues.add(new PendingIssue(null, "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE", "WARNING", true, "Requested product stock is below requested quantity and deterministic substitutes are available", "{\"available\":\"" + available + "\",\"candidateCount\":" + substitutes.size() + "}"));
          } else {
            issues.add(new PendingIssue(null, "NO_SAFE_SUBSTITUTE_FOUND", "ERROR", true, "Available stock is below requested quantity and no safe available substitute was found", "{\"available\":\"" + available + "\"}"));
          }
          if (substitutes.stream().anyMatch(SubstituteCandidate::requiresApproval)) {
            issues.add(new PendingIssue(null, "SUBSTITUTE_REQUIRES_APPROVAL", "WARNING", true, "At least one substitute candidate requires human approval", "{\"candidateCount\":" + substitutes.size() + "}"));
          }
          if (substitutes.stream().anyMatch(SubstituteCandidate::blocked)) {
            issues.add(new PendingIssue(null, "SUBSTITUTE_BLOCKED_FOR_CUSTOMER", "WARNING", true, "At least one substitute is blocked for this customer", "{\"candidateCount\":" + substitutes.size() + "}"));
          }
          if (substitutes.stream().anyMatch(c -> "COMPATIBILITY_UNVERIFIED".equals(c.reasonCode()))) {
            issues.add(new PendingIssue(null, "COMPATIBILITY_UNVERIFIED", "WARNING", true, "At least one substitute has unverified compatibility and requires review", "{\"candidateCount\":" + substitutes.size() + "}"));
          }
        }
      }
      if (marginRuleRepository.findByTenantIdAndActiveTrue(tenantId).isEmpty() || p.getCost() == null || unitPrice == null) {
        issues.add(new PendingIssue(null, "MARGIN_NOT_EVALUATED", "WARNING", false, "Margin was not evaluated in Stage 11A", "{}"));
      }
      return new LineResolution(p.getId(), p.getName(), match.normalizedCode(), unitPrice, currency, available, match.confidence(), issues);
    }
    return new LineResolution(null, null, match.normalizedCode(), null, null, null, match.confidence(), issues);
  }

  private static boolean hasSafeAvailableCandidate(List<SubstituteCandidate> substitutes) {
    return substitutes.stream().anyMatch(candidate -> !candidate.blocked() && "AVAILABLE".equals(candidate.stockStatus().name()));
  }

  private static List<SubstituteCandidateResponse> substituteResponses(List<SubstituteCandidate> candidates) {
    return candidates.stream()
        .map(candidate -> new SubstituteCandidateResponse(
            candidate.productId(),
            candidate.sku(),
            candidate.productName(),
            candidate.relationType().name(),
            candidate.riskLevel().name(),
            candidate.compatibilityMatchReason().name(),
            candidate.reasonCode(),
            candidate.matchedSource(),
            candidate.availableStock(),
            candidate.stockStatus().name(),
            candidate.requiresApproval(),
            candidate.blocked(),
            candidate.customerAccepted(),
            candidate.explanation()))
        .toList();
  }

  private List<RfqLineInput> normalizedLines(CreateDraftQuoteFromRfqRequest command) {
    if (command.lineItems() != null && !command.lineItems().isEmpty()) {
      return command.lineItems();
    }
    if (isBlank(command.rawMessageText())) {
      return List.of();
    }
    Matcher matcher = QUANTITY_PATTERN.matcher(command.rawMessageText());
    BigDecimal quantity = BigDecimal.ONE;
    String uom = "EA";
    if (matcher.find()) {
      quantity = new BigDecimal(matcher.group(1).replace(',', '.'));
      uom = matcher.group(2);
    }
    return List.of(new RfqLineInput(command.rawMessageText(), null, quantity, uom, null));
  }

  private ActorRole parseRole(String role) {
    if (isBlank(role)) {
      return ActorRole.OPERATOR;
    }
    return ActorRole.valueOf(role);
  }

  private static String sourceType(String sourceType) {
    return isBlank(sourceType) ? "API" : sourceType;
  }

  private static BigDecimal safeQuantity(BigDecimal quantity) {
    return quantity == null ? BigDecimal.ZERO : quantity;
  }

  private static String normalizeUom(String uom) {
    if (isBlank(uom)) {
      return "UNKNOWN";
    }
    String normalized = uom.trim().toLowerCase(Locale.ROOT);
    if (Set.of("pcs", "pc", "units", "unit", "шт", "ea").contains(normalized)) {
      return "EA";
    }
    return "UNKNOWN";
  }

  private static String issueCodesJson(List<PendingIssue> issues) {
    if (issues.isEmpty()) {
      return "[]";
    }
    return "[" + String.join(",", issues.stream().map(issue -> "\"" + escape(issue.code()) + "\"").toList()) + "]";
  }

  private static String substituteDecisionStatus(List<PendingIssue> issues) {
    if (issues.stream().anyMatch(issue -> "SUBSTITUTE_BLOCKED_FOR_CUSTOMER".equals(issue.code()))) {
      return "SUBSTITUTE_BLOCKED";
    }
    if (issues.stream().anyMatch(issue -> "NO_SAFE_SUBSTITUTE_FOUND".equals(issue.code()))) {
      return "NO_SAFE_SUBSTITUTE_FOUND";
    }
    if (issues.stream().anyMatch(issue -> Set.of("SUBSTITUTE_REQUIRES_APPROVAL", "COMPATIBILITY_UNVERIFIED").contains(issue.code()))) {
      return "SUBSTITUTE_APPROVAL_REQUIRED";
    }
    if (issues.stream().anyMatch(issue -> "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE".equals(issue.code()))) {
      return "SUBSTITUTE_SUGGESTED";
    }
    return "NO_SUBSTITUTE_REQUIRED";
  }

  private static String substituteDecisionReasonCode(List<PendingIssue> issues) {
    return issues.stream()
        .map(PendingIssue::code)
        .filter(code -> Set.of("SUBSTITUTE_BLOCKED_FOR_CUSTOMER", "NO_SAFE_SUBSTITUTE_FOUND", "SUBSTITUTE_REQUIRES_APPROVAL", "COMPATIBILITY_UNVERIFIED", "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE").contains(code))
        .findFirst()
        .orElse(null);
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (!isBlank(value)) {
        return value;
      }
    }
    return null;
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value.trim();
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

  private record LineResolution(UUID productId, String productName, String normalizedCode, BigDecimal unitPrice, String currency, BigDecimal availableStock, BigDecimal confidence, List<PendingIssue> issues) {
    boolean blocking() {
      return issues.stream().anyMatch(PendingIssue::blocking);
    }
  }
}
