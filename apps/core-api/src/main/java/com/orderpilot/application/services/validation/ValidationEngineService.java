package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationEngineDtos.ApprovalRequirementView;
import com.orderpilot.api.dto.ValidationEngineDtos.CustomerCandidate;
import com.orderpilot.api.dto.ValidationEngineDtos.ExtractedRequestValidationResult;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidateExtractedRequestCommand;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationIssueView;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationLineInput;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationLineResult;
import com.orderpilot.api.dto.ProductIntelligenceDtos.ProductIntelligenceIssue;
import com.orderpilot.api.dto.ProductIntelligenceDtos.ProductResolutionResult;
import com.orderpilot.api.dto.ProductIntelligenceDtos.SubstituteCandidate;
import com.orderpilot.api.dto.ProductIntelligenceDtos.VehicleContext;
import com.orderpilot.application.services.product.ProductIntelligenceService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.SubstituteRiskLevel;
import com.orderpilot.domain.risk.ApprovalRequirementType;
import com.orderpilot.domain.risk.ValidationRiskDecision;
import com.orderpilot.domain.validation.ValidationCaseStatus;
import com.orderpilot.domain.validation.ValidationIssueType;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-08A deterministic Validation &amp; Risk Engine foundation.
 *
 * <p>Takes an advisory extracted request (from the AI worker understanding pipeline) and produces a
 * deterministic, tenant-scoped validation result with issues, a risk decision and approval
 * requirements. It is the deterministic counterpart to advisory AI output.
 *
 * <p>Hard boundaries (all enforced by construction — this service only ever <b>reads</b> repositories):
 * it never creates a quote/order, never approves anything, never mutates product/customer/inventory/
 * price/margin/discount data, and never triggers any external/ERP write. This slice is stateless
 * (no validation-case persistence), so it emits no audit event: there is no state change to audit.
 */
@Service
public class ValidationEngineService {
  private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
  private static final Duration STALE_THRESHOLD = Duration.ofHours(48);
  private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("100000");
  private static final BigDecimal DEFAULT_MARGIN_GUARDRAIL_PERCENT = new BigDecimal("15");
  private static final int MAX_EXPLANATION = 25;
  private static final Set<String> SUPPORTED_INTENTS = Set.of(
      "RFQ", "REQUEST_QUOTE", "PURCHASE_ORDER", "AVAILABILITY_INQUIRY", "PRICE_INQUIRY",
      "SUBSTITUTE_REQUEST", "ORDER_STATUS_INQUIRY");

  private final ProductRepository productRepository;
  private final CustomerAccountRepository customerAccountRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final PriceRuleRepository priceRuleRepository;
  private final MarginRuleRepository marginRuleRepository;
  private final ProductIntelligenceService productIntelligenceService;
  private final Clock clock;

  public ValidationEngineService(
      ProductRepository productRepository,
      CustomerAccountRepository customerAccountRepository,
      InventorySnapshotRepository inventorySnapshotRepository,
      PriceRuleRepository priceRuleRepository,
      MarginRuleRepository marginRuleRepository,
      ProductIntelligenceService productIntelligenceService,
      Clock clock) {
    this.productRepository = productRepository;
    this.customerAccountRepository = customerAccountRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.priceRuleRepository = priceRuleRepository;
    this.marginRuleRepository = marginRuleRepository;
    this.productIntelligenceService = productIntelligenceService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ExtractedRequestValidationResult validate(ValidateExtractedRequestCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    List<ValidationIssueView> caseIssues = new ArrayList<>();
    List<ApprovalRequirementView> approvals = new ArrayList<>();

    // Case-level signals first.
    List<String> signals = command.promptInjectionSignals() == null ? List.of() : command.promptInjectionSignals();
    if (!signals.isEmpty()) {
      caseIssues.add(issue(ValidationIssueType.PROMPT_INJECTION_FLAGGED, ValidationSeverity.WARNING, null,
          "Prompt injection signals present (count=" + signals.size() + "); forced to operator review"));
    }
    if (command.documentConfidence() != null && command.documentConfidence() < LOW_CONFIDENCE_THRESHOLD) {
      caseIssues.add(issue(ValidationIssueType.LOW_EXTRACTION_CONFIDENCE, ValidationSeverity.WARNING, null,
          "Low document extraction confidence; operator review required"));
    }
    if (!isSupportedIntent(command.intent())) {
      caseIssues.add(issue(ValidationIssueType.UNSUPPORTED_INTENT, ValidationSeverity.WARNING, null,
          "Unsupported or missing intent; operator review required"));
    }

    CustomerCandidate matchedCustomer = matchCustomer(tenantId, command, caseIssues);
    UUID matchedCustomerId = matchedCustomer == null ? null : matchedCustomer.customerAccountId();

    List<ValidationLineInput> lines = command.lines() == null ? List.of() : command.lines();
    List<ValidationLineResult> lineResults = new ArrayList<>();
    BigDecimal estimatedTotal = BigDecimal.ZERO;
    boolean anyPriced = false;
    for (ValidationLineInput line : lines) {
      LineOutcome outcome = processLine(tenantId, matchedCustomerId, command.requestedLocationId(), line);
      lineResults.add(outcome.result());
      caseIssues.addAll(outcome.issues());
      approvals.addAll(outcome.approvals());
      if (outcome.estimatedAmount() != null) {
        estimatedTotal = estimatedTotal.add(outcome.estimatedAmount());
        anyPriced = true;
      }
    }

    if (anyPriced && estimatedTotal.compareTo(HIGH_VALUE_THRESHOLD) > 0) {
      caseIssues.add(issue(ValidationIssueType.HIGH_VALUE_REQUIRES_APPROVAL, ValidationSeverity.WARNING, null,
          "Estimated request value exceeds the high-value threshold; manager approval required"));
      approvals.add(new ApprovalRequirementView(ApprovalRequirementType.HIGH_VALUE_APPROVAL, null,
          "Estimated value above high-value threshold"));
    }

    ValidationRiskDecision decision = routeDecision(caseIssues, approvals, !signals.isEmpty());
    ValidationCaseStatus status = statusFor(decision);
    int riskScore = riskScore(caseIssues, approvals, !signals.isEmpty());

    return new ExtractedRequestValidationResult(
        null,
        UUID.randomUUID().toString(),
        command.sourceType(),
        command.sourceId(),
        status,
        decision,
        command.documentConfidence(),
        riskScore,
        matchedCustomer,
        lineResults,
        List.copyOf(caseIssues),
        List.copyOf(approvals),
        explanation(caseIssues));
  }

  private CustomerCandidate matchCustomer(UUID tenantId, ValidateExtractedRequestCommand command, List<ValidationIssueView> issues) {
    if (command.customerAccountId() != null) {
      Optional<CustomerAccount> account = customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(command.customerAccountId(), tenantId);
      if (account.isPresent()) {
        return customerView(account.get(), "EXPLICIT_ID");
      }
      issues.add(issue(ValidationIssueType.CUSTOMER_NOT_FOUND, ValidationSeverity.WARNING, null,
          "Requested customer account id not found for this tenant"));
      return null;
    }
    String hint = command.customerHint();
    if (hint != null && !hint.isBlank()) {
      Optional<CustomerAccount> account = customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, hint.trim());
      if (account.isPresent()) {
        return customerView(account.get(), "ACCOUNT_CODE");
      }
      issues.add(issue(ValidationIssueType.CUSTOMER_NOT_FOUND, ValidationSeverity.WARNING, null,
          "No customer account matched the provided hint by account code"));
      return null;
    }
    issues.add(issue(ValidationIssueType.CUSTOMER_NOT_FOUND, ValidationSeverity.WARNING, null,
        "No customer id or hint provided; operator must confirm customer"));
    return null;
  }

  private CustomerCandidate customerView(CustomerAccount account, String matchType) {
    return new CustomerCandidate(account.getId(), account.getAccountCode(), account.getDisplayName(), matchType);
  }

  private LineOutcome processLine(UUID tenantId, UUID matchedCustomerId, UUID requestedLocationId, ValidationLineInput line) {
    List<ValidationIssueView> issues = new ArrayList<>();
    List<ValidationIssueView> piIssues = new ArrayList<>();
    List<ApprovalRequirementView> approvals = new ArrayList<>();
    int idx = line.lineIndex();

    validateQuantity(line, idx, issues);
    String normalizedUom = validateUom(line, idx, issues);

    // OP-CAP-09A: delegate product resolution + compatibility to the product intelligence service.
    VehicleContext vehicle = new VehicleContext(line.vehicleMake(), line.vehicleModel(), line.vehicleYear(), line.vehicleConfiguration());
    ProductResolutionResult resolution = productIntelligenceService.resolveRequestedProduct(
        tenantId, line.rawProductText(), line.rawSkuOrOem(), vehicle);
    for (ProductIntelligenceIssue pi : resolution.issues()) {
      ValidationIssueView view = issue(pi.type(), pi.severity(), idx, pi.message());
      issues.add(view);
      piIssues.add(view);
    }
    UUID productId = resolution.productId();
    String compatibilityStatus = resolution.compatibilityStatus() == null ? null : resolution.compatibilityStatus().name();
    String matchConfidence = resolution.confidence() == null ? null : resolution.confidence().name();

    boolean substituteRequired = false;
    String inventoryStatus = "UNKNOWN";
    String priceStatus = "UNKNOWN";
    String marginStatus = "UNKNOWN";
    BigDecimal estimatedAmount = null;
    List<SubstituteCandidate> substituteCandidates = List.of();

    if (productId != null) {
      InventoryOutcome inv = checkInventory(tenantId, productId, requestedLocationId, line, idx, issues);
      inventoryStatus = inv.status();
      if (inv.substituteRequired()) {
        substituteRequired = true;
        ValidationIssueView req = issue(ValidationIssueType.SUBSTITUTE_REQUIRED, ValidationSeverity.WARNING, idx,
            "Matched item is unavailable; substitute required");
        issues.add(req);
        piIssues.add(req);
        substituteCandidates = productIntelligenceService.findSubstituteCandidates(
            tenantId, productId, matchedCustomerId, requestedLocationId, line.quantity(), vehicle);
        applySubstituteSignals(substituteCandidates, idx, issues, piIssues, approvals);
      }
      Product matchedProduct = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId).orElse(null);
      PriceOutcome price = checkPrice(tenantId, matchedCustomerId, productId, line, idx, issues);
      priceStatus = price.status();
      if (price.unitPrice() != null && matchedProduct != null) {
        marginStatus = checkMargin(tenantId, matchedProduct, price.unitPrice(), line, idx, issues, approvals);
        if (line.quantity() != null && line.quantity().signum() > 0) {
          estimatedAmount = price.unitPrice().multiply(line.quantity());
        }
      }
    } else if (resolution.unmatched()) {
      substituteRequired = true;
      ValidationIssueView req = issue(ValidationIssueType.SUBSTITUTE_REQUIRED, ValidationSeverity.WARNING, idx,
          "Requested item not matched to a product; substitute required");
      issues.add(req);
      piIssues.add(req);
    }

    if (line.confidence() != null && line.confidence() < LOW_CONFIDENCE_THRESHOLD) {
      issues.add(issue(ValidationIssueType.LOW_EXTRACTION_CONFIDENCE, ValidationSeverity.WARNING, idx,
          "Low line extraction confidence; operator review required"));
    }

    ValidationLineResult result = new ValidationLineResult(
        idx, line.rawProductText(), line.rawSkuOrOem(), line.quantity(), normalizedUom,
        resolution.candidate(), resolution.matchType().name(), inventoryStatus, priceStatus, marginStatus,
        substituteRequired, List.copyOf(issues), matchConfidence, compatibilityStatus,
        substituteCandidates, List.copyOf(piIssues));
    return new LineOutcome(result, issues, approvals, estimatedAmount);
  }

  /**
   * Derive deterministic validation signals from the ranked substitute candidate set. No substitute
   * is ever auto-approved: blocked candidates force review, risky candidates require manager approval.
   */
  private void applySubstituteSignals(List<SubstituteCandidate> candidates, int idx,
      List<ValidationIssueView> issues, List<ValidationIssueView> piIssues, List<ApprovalRequirementView> approvals) {
    if (candidates.isEmpty()) {
      return;
    }
    addBoth(issues, piIssues, issue(ValidationIssueType.SUBSTITUTE_CANDIDATE_FOUND, ValidationSeverity.INFO, idx,
        candidates.size() + " substitute candidate(s) found"));
    if (candidates.stream().anyMatch(c -> c.customerPreferenceStatus() == com.orderpilot.domain.product.CustomerPreferenceStatus.BLOCKED)) {
      addBoth(issues, piIssues, issue(ValidationIssueType.CUSTOMER_SUBSTITUTE_BLOCKED, ValidationSeverity.WARNING, idx,
          "One or more substitutes are blocked by a customer rule"));
    }
    List<SubstituteCandidate> usable = candidates.stream().filter(c -> !c.blocked()).toList();
    if (usable.isEmpty()) {
      addBoth(issues, piIssues, issue(ValidationIssueType.SUBSTITUTE_BLOCKED, ValidationSeverity.WARNING, idx,
          "All substitute candidates are blocked; operator review required"));
      return;
    }
    boolean risky = usable.stream().anyMatch(c -> c.requiresApproval() || c.riskLevel() == SubstituteRiskLevel.HIGH);
    if (risky) {
      addBoth(issues, piIssues, issue(ValidationIssueType.SUBSTITUTE_REQUIRES_APPROVAL, ValidationSeverity.WARNING, idx,
          "A substitute candidate requires manager approval before use"));
      approvals.add(new ApprovalRequirementView(ApprovalRequirementType.SUBSTITUTE_APPROVAL, idx,
          "Risky/high-risk substitute requires approval"));
    }
  }

  private void addBoth(List<ValidationIssueView> issues, List<ValidationIssueView> piIssues, ValidationIssueView view) {
    issues.add(view);
    piIssues.add(view);
  }

  private void validateQuantity(ValidationLineInput line, int idx, List<ValidationIssueView> issues) {
    BigDecimal q = line.quantity();
    if (q == null) {
      issues.add(issue(ValidationIssueType.INVALID_QUANTITY, ValidationSeverity.WARNING, idx, "Quantity missing"));
    } else if (q.signum() <= 0) {
      issues.add(issue(ValidationIssueType.INVALID_QUANTITY, ValidationSeverity.CRITICAL, idx,
          "Quantity must be a positive value"));
    }
  }

  private String validateUom(ValidationLineInput line, int idx, List<ValidationIssueView> issues) {
    String raw = line.uom();
    if (raw == null || raw.isBlank()) {
      issues.add(issue(ValidationIssueType.INVALID_UOM, ValidationSeverity.WARNING, idx, "Unit of measure missing"));
      return null;
    }
    String canonical = canonicalUom(raw);
    if (canonical == null) {
      issues.add(issue(ValidationIssueType.INVALID_UOM, ValidationSeverity.WARNING, idx,
          "Unrecognized unit of measure"));
      return raw.trim().toUpperCase();
    }
    if (!canonical.equalsIgnoreCase(raw.trim())) {
      issues.add(issue(ValidationIssueType.UOM_NORMALIZED, ValidationSeverity.INFO, idx,
          "Unit of measure normalized to " + canonical));
    }
    return canonical;
  }

  private String canonicalUom(String raw) {
    return switch (raw.trim().toLowerCase()) {
      case "pcs", "pc", "piece", "pieces" -> "PCS";
      case "ea", "each" -> "EA";
      case "set", "kit" -> "SET";
      case "box" -> "BOX";
      default -> null;
    };
  }

  private InventoryOutcome checkInventory(UUID tenantId, UUID productId, UUID requestedLocationId, ValidationLineInput line, int idx, List<ValidationIssueView> issues) {
    List<InventorySnapshot> snapshots = lookupSnapshots(tenantId, productId, requestedLocationId);
    if (snapshots.isEmpty()) {
      return new InventoryOutcome("UNKNOWN", false);
    }
    InventorySnapshot latest = snapshots.get(0);
    if (latest.getCapturedAt() != null && latest.getCapturedAt().isBefore(clock.instant().minus(STALE_THRESHOLD))) {
      issues.add(issue(ValidationIssueType.INVENTORY_STALE, ValidationSeverity.WARNING, idx,
          "Latest inventory snapshot is stale"));
    }
    BigDecimal available = latest.getQuantityAvailable() == null ? BigDecimal.ZERO : latest.getQuantityAvailable();
    if (available.signum() <= 0) {
      issues.add(issue(ValidationIssueType.INVENTORY_UNAVAILABLE, ValidationSeverity.WARNING, idx,
          "Requested item is not available in inventory"));
      return new InventoryOutcome("UNAVAILABLE", true);
    }
    if (line.quantity() != null && line.quantity().signum() > 0 && available.compareTo(line.quantity()) < 0) {
      issues.add(issue(ValidationIssueType.LOW_STOCK, ValidationSeverity.WARNING, idx,
          "Available inventory is below the requested quantity"));
      return new InventoryOutcome("PARTIAL", false);
    }
    return new InventoryOutcome("AVAILABLE", false);
  }

  private List<InventorySnapshot> lookupSnapshots(UUID tenantId, UUID productId, UUID locationId) {
    if (locationId != null) {
      return inventorySnapshotRepository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId);
    }
    return inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId);
  }

  private PriceOutcome checkPrice(UUID tenantId, UUID customerId, UUID productId, ValidationLineInput line, int idx, List<ValidationIssueView> issues) {
    Instant now = clock.instant();
    BigDecimal qty = line.quantity();
    Optional<PriceRule> match = priceRuleRepository.findByTenantIdOrderByPriorityAsc(tenantId).stream()
        .filter(PriceRule::isActive)
        .filter(r -> productId.equals(r.getProductId()))
        .filter(r -> r.getCustomerAccountId() == null || r.getCustomerAccountId().equals(customerId))
        .filter(r -> qty == null || r.getMinQuantity() == null || qty.compareTo(r.getMinQuantity()) >= 0)
        .filter(r -> withinWindow(r, now))
        .findFirst();
    if (match.isPresent()) {
      return new PriceOutcome("AVAILABLE", match.get().getUnitPrice());
    }
    issues.add(issue(ValidationIssueType.PRICE_NOT_FOUND, ValidationSeverity.WARNING, idx,
        "No active price found for the customer/product/quantity"));
    return new PriceOutcome("NOT_FOUND", null);
  }

  private boolean withinWindow(PriceRule rule, Instant now) {
    boolean started = rule.getActiveFrom() == null || !now.isBefore(rule.getActiveFrom());
    boolean notEnded = rule.getActiveTo() == null || now.isBefore(rule.getActiveTo());
    return started && notEnded;
  }

  private String checkMargin(UUID tenantId, Product product, BigDecimal unitPrice, ValidationLineInput line, int idx,
      List<ValidationIssueView> issues, List<ApprovalRequirementView> approvals) {
    BigDecimal discount = line.requestedDiscountPercent();
    BigDecimal cost = product.getCost();
    BigDecimal effectivePrice = unitPrice;
    if (discount != null && discount.signum() > 0) {
      effectivePrice = unitPrice.multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)));
    }
    if (cost == null || effectivePrice.signum() <= 0) {
      if (discount != null && discount.signum() > 0) {
        issues.add(issue(ValidationIssueType.DISCOUNT_REQUIRES_APPROVAL, ValidationSeverity.WARNING, idx,
            "Requested discount cannot be margin-verified (missing cost); approval required"));
        approvals.add(new ApprovalRequirementView(ApprovalRequirementType.DISCOUNT_APPROVAL, idx,
            "Discount requested without verifiable margin"));
      }
      return "UNKNOWN";
    }
    BigDecimal marginPercent = effectivePrice.subtract(cost)
        .divide(effectivePrice, 6, RoundingMode.HALF_UP)
        .movePointRight(2);
    BigDecimal guardrail = guardrailPercent(tenantId, product);
    if (marginPercent.compareTo(guardrail) < 0) {
      issues.add(issue(ValidationIssueType.MARGIN_BELOW_GUARDRAIL, ValidationSeverity.WARNING, idx,
          "Margin is below the configured guardrail; manager approval required"));
      approvals.add(new ApprovalRequirementView(ApprovalRequirementType.MARGIN_GUARDRAIL_APPROVAL, idx,
          "Gross margin below guardrail"));
      if (discount != null && discount.signum() > 0) {
        approvals.add(new ApprovalRequirementView(ApprovalRequirementType.DISCOUNT_APPROVAL, idx,
            "Requested discount drives margin below guardrail"));
      }
      return "BELOW_GUARDRAIL";
    }
    return "OK";
  }

  private BigDecimal guardrailPercent(UUID tenantId, Product product) {
    List<MarginRule> rules = marginRuleRepository.findByTenantIdAndActiveTrue(tenantId);
    return rules.stream()
        .filter(r -> product.getId().equals(r.getProductId())
            || (r.getProductId() == null && r.getCategory() == null)
            || (r.getCategory() != null && r.getCategory().equalsIgnoreCase(product.getCategory())))
        // most specific first: product-scoped, then category, then global
        .sorted((a, b) -> rank(b, product) - rank(a, product))
        .map(MarginRule::getApprovalRequiredBelowPercent)
        .findFirst()
        .orElse(DEFAULT_MARGIN_GUARDRAIL_PERCENT);
  }

  private int rank(MarginRule rule, Product product) {
    if (product.getId().equals(rule.getProductId())) {
      return 3;
    }
    if (rule.getCategory() != null && rule.getCategory().equalsIgnoreCase(product.getCategory())) {
      return 2;
    }
    return 1;
  }

  private ValidationRiskDecision routeDecision(List<ValidationIssueView> issues, List<ApprovalRequirementView> approvals, boolean injection) {
    boolean blocking = issues.stream().anyMatch(i -> i.severity() == ValidationSeverity.CRITICAL || i.severity() == ValidationSeverity.ERROR);
    if (blocking) {
      return ValidationRiskDecision.BLOCKED;
    }
    ValidationRiskDecision decision = ValidationRiskDecision.AUTO_READY_DRAFT;
    if (!approvals.isEmpty()) {
      decision = ValidationRiskDecision.REQUIRES_MANAGER_APPROVAL;
    }
    boolean anyWarning = issues.stream().anyMatch(i -> i.severity() == ValidationSeverity.WARNING);
    if (injection || anyWarning) {
      decision = strongest(decision, ValidationRiskDecision.NEEDS_OPERATOR_REVIEW);
    }
    return decision;
  }

  private ValidationRiskDecision strongest(ValidationRiskDecision a, ValidationRiskDecision b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  private ValidationCaseStatus statusFor(ValidationRiskDecision decision) {
    return switch (decision) {
      case BLOCKED -> ValidationCaseStatus.BLOCKED;
      case AUTO_READY_DRAFT -> ValidationCaseStatus.VALIDATED;
      case NEEDS_OPERATOR_REVIEW, REQUIRES_MANAGER_APPROVAL -> ValidationCaseStatus.NEEDS_REVIEW;
    };
  }

  private int riskScore(List<ValidationIssueView> issues, List<ApprovalRequirementView> approvals, boolean injection) {
    int score = 0;
    for (ValidationIssueView i : issues) {
      score += switch (i.severity()) {
        case CRITICAL -> 50;
        case ERROR -> 40;
        case WARNING -> 10;
        case INFO -> 0;
      };
    }
    score += approvals.size() * 25;
    if (injection) {
      score += 30;
    }
    return Math.min(score, 100);
  }

  private List<String> explanation(List<ValidationIssueView> issues) {
    return issues.stream()
        .map(i -> (i.lineIndex() == null ? "request" : "line " + i.lineIndex()) + ": " + i.message())
        .distinct()
        .limit(MAX_EXPLANATION)
        .toList();
  }

  private ValidationIssueView issue(ValidationIssueType type, ValidationSeverity severity, Integer lineIndex, String message) {
    return new ValidationIssueView(type, severity, lineIndex, message);
  }

  private boolean isSupportedIntent(String intent) {
    if (intent == null || intent.isBlank()) {
      return false;
    }
    return SUPPORTED_INTENTS.contains(intent.trim().toUpperCase().replace(' ', '_'));
  }

  private record InventoryOutcome(String status, boolean substituteRequired) {}

  private record PriceOutcome(String status, BigDecimal unitPrice) {}

  private record LineOutcome(ValidationLineResult result, List<ValidationIssueView> issues, List<ApprovalRequirementView> approvals, BigDecimal estimatedAmount) {}
}
