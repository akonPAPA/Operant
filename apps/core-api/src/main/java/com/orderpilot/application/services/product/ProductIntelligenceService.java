package com.orderpilot.application.services.product;

import com.orderpilot.api.dto.ProductIntelligenceDtos.CompatibilityEvidence;
import com.orderpilot.api.dto.ProductIntelligenceDtos.ProductIntelligenceIssue;
import com.orderpilot.api.dto.ProductIntelligenceDtos.ProductResolutionResult;
import com.orderpilot.api.dto.ProductIntelligenceDtos.SubstituteCandidate;
import com.orderpilot.api.dto.ProductIntelligenceDtos.VehicleContext;
import com.orderpilot.api.dto.ValidationEngineDtos.ProductCandidate;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.product.CompatibilityStatus;
import com.orderpilot.domain.product.CustomerPreferenceStatus;
import com.orderpilot.domain.product.CustomerSubstitutionPreference;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibility;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductMatchConfidence;
import com.orderpilot.domain.product.ProductMatchType;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.product.SubstituteReason;
import com.orderpilot.domain.product.SubstituteRiskLevel;
import com.orderpilot.domain.validation.ValidationIssueType;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-09A Product Intelligence + Substitution Foundation.
 *
 * <p>Reusable, tenant-scoped, strictly <b>read-only</b> service that explains: what product a request
 * resolved to, why, what substitutes exist, why a substitute is compatible/risky, and whether human
 * approval is needed. It centralizes product resolution so the validation engine delegates here
 * instead of duplicating SKU/alias/OEM logic.
 *
 * <p>Boundaries: never creates a quote/order, never approves anything, never mutates
 * product/customer/inventory/price data, never triggers an external write. All lookups are
 * tenant-scoped, bounded (top-N), and avoid full-table scans. This is the foundation, not the final
 * substitution engine.
 */
@Service
public class ProductIntelligenceService {
  public static final int MAX_SUBSTITUTE_CANDIDATES = 8;
  public static final int MAX_EVIDENCE_ITEMS = 5;
  private static final Duration STALE_THRESHOLD = Duration.ofHours(48);

  private final ProductRepository productRepository;
  private final ProductAliasRepository productAliasRepository;
  private final OEMReferenceRepository oemReferenceRepository;
  private final ProductSubstituteRepository productSubstituteRepository;
  private final ProductCompatibilityRepository productCompatibilityRepository;
  private final CustomerSubstitutionPreferenceRepository customerSubstitutionPreferenceRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final Clock clock;

  public ProductIntelligenceService(
      ProductRepository productRepository,
      ProductAliasRepository productAliasRepository,
      OEMReferenceRepository oemReferenceRepository,
      ProductSubstituteRepository productSubstituteRepository,
      ProductCompatibilityRepository productCompatibilityRepository,
      CustomerSubstitutionPreferenceRepository customerSubstitutionPreferenceRepository,
      InventorySnapshotRepository inventorySnapshotRepository,
      Clock clock) {
    this.productRepository = productRepository;
    this.productAliasRepository = productAliasRepository;
    this.oemReferenceRepository = oemReferenceRepository;
    this.productSubstituteRepository = productSubstituteRepository;
    this.productCompatibilityRepository = productCompatibilityRepository;
    this.customerSubstitutionPreferenceRepository = customerSubstitutionPreferenceRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.clock = clock;
  }

  /**
   * Resolve a requested item to a product. Order: exact SKU &gt; alias &gt; OEM reference &gt;
   * deterministic normalized text candidate &gt; NONE. Never throws on no-match.
   */
  @Transactional(readOnly = true)
  public ProductResolutionResult resolveRequestedProduct(UUID tenantId, String rawProductText, String rawSkuOrOem, VehicleContext vehicle) {
    List<ProductIntelligenceIssue> issues = new ArrayList<>();
    Resolution r = resolveCore(tenantId, rawProductText, rawSkuOrOem, issues);

    CompatibilityStatus compatibilityStatus = CompatibilityStatus.UNKNOWN;
    List<CompatibilityEvidence> evidence = List.of();
    if (r.product() != null) {
      CompatibilityResult comp = explainCompatibility(tenantId, r.product().getId(), vehicle);
      compatibilityStatus = comp.status();
      evidence = comp.evidence();
      issues.addAll(comp.issues());
    }

    ProductCandidate candidate = r.product() == null ? null
        : new ProductCandidate(r.product().getId(), r.product().getSku(), r.product().getName(),
            r.matchType().name(), confidenceDecimal(r.confidence()));
    return new ProductResolutionResult(
        r.matchType(), r.confidence(), r.product() == null ? null : r.product().getId(),
        candidate, r.ambiguous(), r.noMatch(), compatibilityStatus, evidence, issues);
  }

  /** Explain compatibility of a product against requested vehicle/equipment context. Never fabricates. */
  @Transactional(readOnly = true)
  public CompatibilityResult explainCompatibility(UUID tenantId, UUID productId, VehicleContext vehicle) {
    List<ProductCompatibility> rows = productCompatibilityRepository.findByTenantIdAndProductIdAndActiveTrue(tenantId, productId);
    if (rows.isEmpty()) {
      return new CompatibilityResult(CompatibilityStatus.UNKNOWN, List.of(),
          List.of(new ProductIntelligenceIssue(ValidationIssueType.COMPATIBILITY_UNKNOWN, ValidationSeverity.INFO,
              "No compatibility/fitment data available for the matched product")));
    }
    List<CompatibilityEvidence> evidence = rows.stream().limit(MAX_EVIDENCE_ITEMS).map(this::evidenceOf).toList();
    if (vehicle == null || !vehicle.isPresent()) {
      // Missing vehicle context must never invalidate a match; report UNKNOWN without an issue.
      return new CompatibilityResult(CompatibilityStatus.UNKNOWN, evidence, List.of());
    }
    CompatibilityStatus status = assessCompatibility(rows, vehicle);
    List<ProductIntelligenceIssue> issues = status == CompatibilityStatus.CONFLICT
        ? List.of(new ProductIntelligenceIssue(ValidationIssueType.COMPATIBILITY_CONFLICT, ValidationSeverity.WARNING,
            "Requested vehicle/equipment conflicts with product fitment data"))
        : List.of();
    return new CompatibilityResult(status, evidence, issues);
  }

  /**
   * Find ranked substitute candidates for an unavailable/at-risk source product. Bounded and
   * deterministic; blocked substitutes are kept but flagged (never auto-offered).
   */
  @Transactional(readOnly = true)
  public List<SubstituteCandidate> findSubstituteCandidates(UUID tenantId, UUID sourceProductId, UUID customerAccountId, UUID locationId, BigDecimal quantity, VehicleContext vehicle) {
    if (sourceProductId == null) {
      return List.of();
    }
    List<ProductSubstitute> subs = productSubstituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, sourceProductId).stream()
        .limit(MAX_SUBSTITUTE_CANDIDATES)
        .toList();
    if (subs.isEmpty()) {
      return List.of();
    }
    List<CustomerSubstitutionPreference> prefs = customerAccountId == null ? List.of()
        : customerSubstitutionPreferenceRepository.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId);

    List<SubstituteCandidate> candidates = new ArrayList<>();
    for (ProductSubstitute sub : subs) {
      Optional<Product> product = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(sub.getSubstituteProductId(), tenantId);
      if (product.isEmpty()) {
        continue;
      }
      candidates.add(buildCandidate(tenantId, sourceProductId, sub, product.get(), prefs, locationId, quantity, vehicle));
    }
    candidates.sort(RANKING);
    return candidates.stream().limit(MAX_SUBSTITUTE_CANDIDATES).toList();
  }

  private Resolution resolveCore(UUID tenantId, String rawProductText, String rawSkuOrOem, List<ProductIntelligenceIssue> issues) {
    if (rawSkuOrOem != null && !rawSkuOrOem.isBlank()) {
      return resolveByCode(tenantId, ProductCodeNormalizer.normalize(rawSkuOrOem), issues);
    }
    if (rawProductText != null && !rawProductText.isBlank()) {
      return resolveByText(tenantId, rawProductText.trim(), issues);
    }
    issues.add(notFound("No SKU/OEM reference or product text provided; product could not be matched"));
    return Resolution.unmatched();
  }

  private Resolution resolveByCode(UUID tenantId, String norm, List<ProductIntelligenceIssue> issues) {
    List<Product> bySku = productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE");
    if (bySku.size() == 1) {
      return new Resolution(ProductMatchType.EXACT_SKU, ProductMatchConfidence.HIGH, bySku.get(0), false, false);
    }
    if (bySku.size() > 1) {
      return ambiguous(issues);
    }
    Set<UUID> aliasProducts = distinctIds(productAliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, norm).stream()
        .map(a -> a.getProductId()).toList());
    if (aliasProducts.size() == 1) {
      issues.add(info(ValidationIssueType.PRODUCT_ALIAS_MATCHED, "Matched by product alias"));
      return loadMatch(tenantId, ProductMatchType.ALIAS, ProductMatchConfidence.MEDIUM, aliasProducts.iterator().next(), issues);
    }
    if (aliasProducts.size() > 1) {
      return ambiguous(issues);
    }
    Set<UUID> oemProducts = distinctIds(oemReferenceRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, norm).stream()
        .map(o -> o.getProductId()).toList());
    if (oemProducts.size() == 1) {
      issues.add(info(ValidationIssueType.OEM_MATCHED, "Matched by OEM reference"));
      return loadMatch(tenantId, ProductMatchType.OEM_REFERENCE, ProductMatchConfidence.MEDIUM, oemProducts.iterator().next(), issues);
    }
    if (oemProducts.size() > 1) {
      return ambiguous(issues);
    }
    issues.add(notFound("No product matched the provided SKU/OEM reference"));
    return Resolution.unmatched();
  }

  private Resolution resolveByText(UUID tenantId, String text, List<ProductIntelligenceIssue> issues) {
    // Bounded (top-25) deterministic text candidate. Not fuzzy/semantic search.
    List<Product> candidates = productRepository
        .findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
            tenantId, text, tenantId, text);
    Set<UUID> ids = distinctIds(candidates.stream().map(Product::getId).toList());
    if (ids.size() == 1) {
      issues.add(new ProductIntelligenceIssue(ValidationIssueType.PRODUCT_TEXT_CANDIDATE_LOW_CONFIDENCE,
          ValidationSeverity.WARNING, "Resolved by deterministic text candidate; low confidence, operator review required"));
      return loadMatch(tenantId, ProductMatchType.TEXT_CANDIDATE, ProductMatchConfidence.LOW, ids.iterator().next(), issues);
    }
    if (ids.size() > 1) {
      return ambiguous(issues);
    }
    issues.add(notFound("No product matched the provided product text"));
    return Resolution.unmatched();
  }

  private Resolution loadMatch(UUID tenantId, ProductMatchType type, ProductMatchConfidence confidence, UUID productId, List<ProductIntelligenceIssue> issues) {
    Optional<Product> product = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId);
    if (product.isEmpty()) {
      issues.add(notFound("Matched reference points to an unavailable product"));
      return Resolution.unmatched();
    }
    return new Resolution(type, confidence, product.get(), false, false);
  }

  private Resolution ambiguous(List<ProductIntelligenceIssue> issues) {
    issues.add(new ProductIntelligenceIssue(ValidationIssueType.PRODUCT_AMBIGUOUS, ValidationSeverity.WARNING,
        "Multiple product candidates matched; operator must disambiguate"));
    return new Resolution(ProductMatchType.NONE, null, null, true, false);
  }

  private CompatibilityStatus assessCompatibility(List<ProductCompatibility> rows, VehicleContext vehicle) {
    boolean anyMakeModel = false;
    boolean yearConflict = false;
    for (ProductCompatibility row : rows) {
      boolean makeMatch = eq(row.getMake(), vehicle.make());
      boolean modelMatch = eq(row.getModel(), vehicle.model());
      if (makeMatch && modelMatch) {
        anyMakeModel = true;
        boolean yearOk = yearInRange(row, vehicle.year());
        boolean configOk = vehicle.configuration() == null || row.getConfiguration() == null
            || row.getConfiguration().equalsIgnoreCase(vehicle.configuration());
        if (yearOk && configOk) {
          return CompatibilityStatus.CONFIRMED;
        }
        if (!yearOk) {
          yearConflict = true;
        }
      }
    }
    if (yearConflict) {
      return CompatibilityStatus.CONFLICT;
    }
    if (anyMakeModel) {
      return CompatibilityStatus.PARTIAL;
    }
    return CompatibilityStatus.UNKNOWN;
  }

  private boolean yearInRange(ProductCompatibility row, Integer year) {
    if (year == null) {
      return true;
    }
    boolean fromOk = row.getYearFrom() == null || year >= row.getYearFrom();
    boolean toOk = row.getYearTo() == null || year <= row.getYearTo();
    return fromOk && toOk;
  }

  private CompatibilityEvidence evidenceOf(ProductCompatibility row) {
    return new CompatibilityEvidence(row.getCompatibleType(), row.getMake(), row.getModel(),
        row.getYearFrom(), row.getYearTo(), row.getConfiguration(), row.getRiskLevel(), "PRODUCT_COMPATIBILITY");
  }

  private SubstituteCandidate buildCandidate(UUID tenantId, UUID sourceProductId, ProductSubstitute sub, Product product,
      List<CustomerSubstitutionPreference> prefs, UUID locationId, BigDecimal quantity, VehicleContext vehicle) {
    StockOutcome stock = stockStatus(tenantId, product.getId(), locationId, quantity);
    CompatibilityResult comp = explainCompatibility(tenantId, product.getId(), vehicle);
    CustomerPreferenceStatus prefStatus = preferenceStatus(prefs, sourceProductId, sub);
    boolean blocked = prefStatus == CustomerPreferenceStatus.BLOCKED || "BLOCKED".equalsIgnoreCase(sub.getRiskLevel());
    SubstituteRiskLevel risk = deriveRisk(sub, comp.status(), blocked);
    boolean requiresApproval = !blocked && (sub.isRequiresApproval() || risk == SubstituteRiskLevel.HIGH
        || comp.status() == CompatibilityStatus.CONFLICT);
    List<SubstituteReason> reasons = deriveReasons(sub, comp.status(), prefStatus, stock.status());
    String explanation = explain(product, reasons, risk, blocked, stock, comp.status(), prefStatus);
    return new SubstituteCandidate(product.getId(), product.getSku(), product.getName(), sourceProductId,
        reasons, risk, requiresApproval, blocked, stock.status(), stock.availableQuantity(),
        comp.status(), comp.evidence(), prefStatus, explanation);
  }

  private CustomerPreferenceStatus preferenceStatus(List<CustomerSubstitutionPreference> prefs, UUID sourceProductId, ProductSubstitute sub) {
    CustomerPreferenceStatus status = CustomerPreferenceStatus.UNKNOWN;
    for (CustomerSubstitutionPreference pref : prefs) {
      boolean applies = pref.getProductId() == null || pref.getProductId().equals(sourceProductId);
      if (!applies) {
        continue;
      }
      if (sub.getSubstituteProductId().equals(pref.getBlockedSubstituteProductId())) {
        return CustomerPreferenceStatus.BLOCKED;
      }
      if ("AFTERMARKET".equalsIgnoreCase(sub.getSubstituteType())) {
        if (!pref.isAllowAftermarket()) {
          return CustomerPreferenceStatus.BLOCKED;
        }
        status = CustomerPreferenceStatus.ACCEPTED;
      }
    }
    return status;
  }

  private SubstituteRiskLevel deriveRisk(ProductSubstitute sub, CompatibilityStatus compatibility, boolean blocked) {
    if (blocked) {
      return SubstituteRiskLevel.BLOCKED;
    }
    SubstituteRiskLevel base = parseRisk(sub.getRiskLevel());
    if (compatibility == CompatibilityStatus.CONFLICT) {
      return SubstituteRiskLevel.HIGH;
    }
    if (compatibility == CompatibilityStatus.UNKNOWN && base == SubstituteRiskLevel.LOW) {
      return SubstituteRiskLevel.MEDIUM;
    }
    return base;
  }

  private SubstituteRiskLevel parseRisk(String raw) {
    if (raw == null) {
      return SubstituteRiskLevel.MEDIUM;
    }
    try {
      return SubstituteRiskLevel.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return SubstituteRiskLevel.MEDIUM;
    }
  }

  private List<SubstituteReason> deriveReasons(ProductSubstitute sub, CompatibilityStatus compatibility, CustomerPreferenceStatus prefStatus, String stockStatus) {
    List<SubstituteReason> reasons = new ArrayList<>();
    String type = sub.getSubstituteType() == null ? "" : sub.getSubstituteType().toUpperCase();
    if (type.contains("EXACT") || type.contains("IDENTICAL")) {
      reasons.add(SubstituteReason.EXACT_REPLACEMENT);
    }
    if (type.contains("OEM")) {
      reasons.add(SubstituteReason.OEM_EQUIVALENT);
    }
    if (compatibility == CompatibilityStatus.CONFIRMED || compatibility == CompatibilityStatus.PARTIAL) {
      reasons.add(SubstituteReason.COMPATIBLE_WITH_MODEL);
    }
    if (prefStatus == CustomerPreferenceStatus.ACCEPTED || prefStatus == CustomerPreferenceStatus.PREFERRED) {
      reasons.add(SubstituteReason.CUSTOMER_ACCEPTED_BEFORE);
    }
    if (prefStatus == CustomerPreferenceStatus.BLOCKED) {
      reasons.add(SubstituteReason.BLOCKED_BY_CUSTOMER_RULE);
    }
    if ("AVAILABLE".equals(stockStatus)) {
      reasons.add(SubstituteReason.STOCK_PREFERRED);
    }
    if (compatibility == CompatibilityStatus.UNKNOWN) {
      reasons.add(SubstituteReason.LOW_EVIDENCE);
    }
    return reasons;
  }

  private String explain(Product product, List<SubstituteReason> reasons, SubstituteRiskLevel risk, boolean blocked, StockOutcome stock, CompatibilityStatus compatibility, CustomerPreferenceStatus prefStatus) {
    return "Substitute " + product.getSku() + " (" + product.getName() + "): risk=" + risk
        + ", stock=" + stock.status() + ", compatibility=" + compatibility + ", customerPreference=" + prefStatus
        + (blocked ? ", BLOCKED" : "") + ", reasons=" + reasons;
  }

  private StockOutcome stockStatus(UUID tenantId, UUID productId, UUID locationId, BigDecimal quantity) {
    List<InventorySnapshot> snapshots = locationId != null
        ? inventorySnapshotRepository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId)
        : inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId);
    if (snapshots.isEmpty()) {
      return new StockOutcome("UNKNOWN", null);
    }
    InventorySnapshot latest = snapshots.get(0);
    BigDecimal available = latest.getQuantityAvailable() == null ? BigDecimal.ZERO : latest.getQuantityAvailable();
    if (available.signum() <= 0) {
      return new StockOutcome("UNAVAILABLE", available);
    }
    if (quantity != null && quantity.signum() > 0 && available.compareTo(quantity) < 0) {
      return new StockOutcome("PARTIAL", available);
    }
    return new StockOutcome("AVAILABLE", available);
  }

  private static final Comparator<SubstituteCandidate> RANKING = Comparator
      .comparingInt((SubstituteCandidate c) -> c.blocked() ? 1 : 0) // not blocked first
      .thenComparingInt(c -> -compatibilityRank(c.compatibilityStatus()))
      .thenComparingInt(c -> -preferenceRank(c.customerPreferenceStatus()))
      .thenComparingInt(c -> -stockRank(c.stockStatus()))
      .thenComparingInt(c -> riskRank(c.riskLevel()))
      .thenComparingInt(c -> -reasonStrength(c.reasons()))
      .thenComparing(c -> c.substituteProductId().toString());

  private static int compatibilityRank(CompatibilityStatus s) {
    return switch (s) {
      case CONFIRMED -> 3;
      case PARTIAL -> 2;
      case UNKNOWN -> 1;
      case CONFLICT -> 0;
    };
  }

  private static int preferenceRank(CustomerPreferenceStatus s) {
    return switch (s) {
      case PREFERRED -> 3;
      case ACCEPTED -> 2;
      case UNKNOWN -> 1;
      case BLOCKED -> 0;
    };
  }

  private static int stockRank(String s) {
    return switch (s) {
      case "AVAILABLE" -> 3;
      case "PARTIAL" -> 2;
      case "UNKNOWN" -> 1;
      default -> 0;
    };
  }

  private static int riskRank(SubstituteRiskLevel r) {
    return r.ordinal(); // LOW(0) < MEDIUM < HIGH < BLOCKED
  }

  private static int reasonStrength(List<SubstituteReason> reasons) {
    int strength = 0;
    if (reasons.contains(SubstituteReason.EXACT_REPLACEMENT)) {
      strength += 4;
    }
    if (reasons.contains(SubstituteReason.OEM_EQUIVALENT)) {
      strength += 3;
    }
    if (reasons.contains(SubstituteReason.COMPATIBLE_WITH_MODEL)) {
      strength += 2;
    }
    if (reasons.contains(SubstituteReason.CUSTOMER_ACCEPTED_BEFORE)) {
      strength += 1;
    }
    return strength;
  }

  private Set<UUID> distinctIds(List<UUID> ids) {
    return new LinkedHashSet<>(ids);
  }

  private boolean eq(String a, String b) {
    return a != null && b != null && a.equalsIgnoreCase(b.trim());
  }

  private BigDecimal confidenceDecimal(ProductMatchConfidence confidence) {
    if (confidence == null) {
      return null;
    }
    return switch (confidence) {
      case HIGH -> new BigDecimal("0.95");
      case MEDIUM -> new BigDecimal("0.80");
      case LOW -> new BigDecimal("0.50");
    };
  }

  private ProductIntelligenceIssue info(ValidationIssueType type, String message) {
    return new ProductIntelligenceIssue(type, ValidationSeverity.INFO, message);
  }

  private ProductIntelligenceIssue notFound(String message) {
    return new ProductIntelligenceIssue(ValidationIssueType.PRODUCT_NOT_FOUND, ValidationSeverity.WARNING, message);
  }

  /** Compatibility status + bounded evidence + advisory issues. */
  public record CompatibilityResult(CompatibilityStatus status, List<CompatibilityEvidence> evidence, List<ProductIntelligenceIssue> issues) {}

  private record Resolution(ProductMatchType matchType, ProductMatchConfidence confidence, Product product, boolean ambiguous, boolean noMatch) {
    static Resolution unmatched() {
      return new Resolution(ProductMatchType.NONE, null, null, false, true);
    }
  }

  private record StockOutcome(String status, BigDecimal availableQuantity) {}
}
