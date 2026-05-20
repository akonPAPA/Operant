package com.orderpilot.application.services;

import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.product.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSubstitutionService {
  private final ProductRepository productRepository;
  private final ProductSubstituteRepository substituteRepository;
  private final ProductCompatibilityRepository compatibilityRepository;
  private final ProductAliasRepository aliasRepository;
  private final OEMReferenceRepository oemReferenceRepository;
  private final CustomerSubstitutionPreferenceRepository preferenceRepository;
  private final InventorySnapshotRepository inventoryRepository;

  public ProductSubstitutionService(
      ProductRepository productRepository,
      ProductSubstituteRepository substituteRepository,
      ProductCompatibilityRepository compatibilityRepository,
      ProductAliasRepository aliasRepository,
      OEMReferenceRepository oemReferenceRepository,
      CustomerSubstitutionPreferenceRepository preferenceRepository,
      InventorySnapshotRepository inventoryRepository) {
    this.productRepository = productRepository;
    this.substituteRepository = substituteRepository;
    this.compatibilityRepository = compatibilityRepository;
    this.aliasRepository = aliasRepository;
    this.oemReferenceRepository = oemReferenceRepository;
    this.preferenceRepository = preferenceRepository;
    this.inventoryRepository = inventoryRepository;
  }

  @Transactional(readOnly = true)
  public List<SubstituteCandidate> suggest(UUID tenantId, UUID requestedProductId, String rawCode, String rawText, UUID customerAccountId, BigDecimal requestedQuantity) {
    Optional<Product> requested = requestedProductId == null
        ? resolveSourceProduct(tenantId, firstNonBlank(rawCode, rawText), customerAccountId)
        : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(requestedProductId, tenantId);
    if (requested.isEmpty()) {
      return List.of();
    }

    VehicleContext context = VehicleContext.parse(firstNonBlank(rawText, rawCode));
    List<CustomerSubstitutionPreference> preferences = customerAccountId == null ? List.of() : preferenceRepository.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId);
    List<SubstituteCandidate> candidates = new ArrayList<>();
    for (ProductSubstitute substitute : substituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, requested.get().getId())) {
      productRepository.findByIdAndTenantIdAndDeletedAtIsNull(substitute.getSubstituteProductId(), tenantId)
          .filter(product -> "ACTIVE".equals(product.getStatus()))
          .ifPresent(product -> candidates.add(candidate(tenantId, requested.get(), product, substitute, context, preferences, requestedQuantity)));
    }

    candidates.sort(Comparator
        .comparing(SubstituteCandidate::blocked)
        .thenComparing(SubstituteCandidate::requiresApproval)
        .thenComparing((SubstituteCandidate c) -> c.stockStatus() == StockStatus.AVAILABLE ? 0 : c.stockStatus() == StockStatus.LOW_STOCK ? 1 : 2)
        .thenComparing((SubstituteCandidate c) -> riskRank(c.riskLevel()))
        .thenComparing(SubstituteCandidate::relationType)
        .thenComparing(SubstituteCandidate::sku));
    return candidates;
  }

  private Optional<Product> resolveSourceProduct(UUID tenantId, String rawCodeOrText, UUID customerAccountId) {
    String normalized = ProductCodeNormalizer.normalize(rawCodeOrText);
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    List<Product> skuMatches = productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, normalized, "ACTIVE");
    if (skuMatches.size() == 1) {
      return Optional.of(skuMatches.get(0));
    }

    List<ProductAlias> aliases = new ArrayList<>();
    if (customerAccountId != null) {
      aliases.addAll(aliasRepository.findByTenantIdAndNormalizedAliasAndCustomerAccountIdAndActiveTrue(tenantId, normalized, customerAccountId));
    }
    aliases.addAll(aliasRepository.findByTenantIdAndNormalizedAliasAndCustomerAccountIdIsNullAndActiveTrue(tenantId, normalized));
    Optional<Product> aliasMatch = singleProduct(tenantId, aliases.stream().map(ProductAlias::getProductId).toList());
    if (aliasMatch.isPresent()) {
      return aliasMatch;
    }

    List<ProductAlias> embeddedAliases = aliasRepository.findByTenantIdAndActiveTrue(tenantId).stream()
        .filter(alias -> normalized.contains(alias.getNormalizedAlias()))
        .filter(alias -> alias.getCustomerAccountId() == null || alias.getCustomerAccountId().equals(customerAccountId))
        .toList();
    Optional<Product> embeddedAliasMatch = singleProduct(tenantId, embeddedAliases.stream().map(ProductAlias::getProductId).toList());
    if (embeddedAliasMatch.isPresent()) {
      return embeddedAliasMatch;
    }

    return singleProduct(tenantId, oemReferenceRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, normalized).stream().map(OEMReference::getProductId).toList());
  }

  private Optional<Product> singleProduct(UUID tenantId, List<UUID> ids) {
    LinkedHashSet<UUID> distinct = new LinkedHashSet<>(ids);
    List<Product> products = distinct.stream()
        .map(id -> productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId))
        .flatMap(Optional::stream)
        .filter(product -> "ACTIVE".equals(product.getStatus()))
        .toList();
    return products.size() == 1 ? Optional.of(products.get(0)) : Optional.empty();
  }

  private SubstituteCandidate candidate(UUID tenantId, Product requested, Product substituteProduct, ProductSubstitute relation, VehicleContext context, List<CustomerSubstitutionPreference> preferences, BigDecimal requestedQuantity) {
    List<ProductCompatibility> compatibility = compatibilityRepository.findByTenantIdAndProductIdAndActiveTrue(tenantId, substituteProduct.getId());
    CompatibilityDecision compatibilityDecision = compatibilityDecision(compatibility, context);
    BigDecimal available = inventoryRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, substituteProduct.getId()).stream()
        .findFirst()
        .map(InventorySnapshot::getQuantityAvailable)
        .orElse(null);
    StockStatus stockStatus = stockStatus(available, requestedQuantity);
    boolean blocked = preferences.stream().anyMatch(p -> substituteProduct.getId().equals(p.getBlockedSubstituteProductId()) && (p.getProductId() == null || p.getProductId().equals(requested.getId())));
    boolean accepted = !blocked && preferences.stream().anyMatch(p -> p.isAllowAftermarket() && (p.getProductId() == null || p.getProductId().equals(requested.getId())));
    boolean requiresApproval = blocked || relation.isRequiresApproval() || compatibilityDecision.requiresApproval() || "HIGH".equalsIgnoreCase(relation.getRiskLevel());
    SubstituteRelationType relationType = accepted ? SubstituteRelationType.CUSTOMER_ACCEPTED : parseRelationType(relation.getSubstituteType());
    if (blocked) {
      relationType = SubstituteRelationType.CUSTOMER_BLOCKED;
    }
    String reasonCode = blocked ? "CUSTOMER_BLOCKED_RULE"
        : accepted ? "CUSTOMER_ACCEPTED_HISTORY"
        : compatibilityDecision.reasonCode();
    String explanation = "matchedSource=PRODUCT_SUBSTITUTE; relationType=" + relationType
        + "; reasonCode=" + reasonCode
        + "; stockStatus=" + stockStatus
        + "; approval=" + (requiresApproval ? "REQUIRED" : "NOT_REQUIRED");
    return new SubstituteCandidate(
        substituteProduct.getId(),
        substituteProduct.getSku(),
        substituteProduct.getName(),
        relationType,
        parseRiskLevel(relation.getRiskLevel()),
        compatibilityDecision.matchReason(),
        reasonCode,
        "PRODUCT_SUBSTITUTE",
        available,
        stockStatus,
        requiresApproval,
        blocked,
        accepted,
        explanation);
  }

  private CompatibilityDecision compatibilityDecision(List<ProductCompatibility> compatibility, VehicleContext context) {
    if (compatibility.isEmpty()) {
      return new CompatibilityDecision(CompatibilityMatchReason.NOT_CONFIGURED, "COMPATIBILITY_UNVERIFIED", true);
    }
    if (context.empty()) {
      boolean highRisk = compatibility.stream().anyMatch(c -> "HIGH".equalsIgnoreCase(c.getRiskLevel()));
      return new CompatibilityDecision(highRisk ? CompatibilityMatchReason.HIGH_RISK_CONFIGURED : CompatibilityMatchReason.PRODUCT_RELATION_ONLY, highRisk ? "HIGH_RISK_COMPATIBILITY" : "PRODUCT_RELATION", highRisk);
    }
    boolean matched = compatibility.stream().anyMatch(c -> context.matches(c.getMake(), c.getModel(), c.getYearFrom(), c.getYearTo()));
    if (matched) {
      boolean highRisk = compatibility.stream().anyMatch(c -> context.matches(c.getMake(), c.getModel(), c.getYearFrom(), c.getYearTo()) && "HIGH".equalsIgnoreCase(c.getRiskLevel()));
      return new CompatibilityDecision(highRisk ? CompatibilityMatchReason.HIGH_RISK_CONFIGURED : CompatibilityMatchReason.VEHICLE_CONTEXT_MATCH, highRisk ? "HIGH_RISK_COMPATIBILITY" : "VEHICLE_CONTEXT_MATCH", highRisk);
    }
    return new CompatibilityDecision(CompatibilityMatchReason.CONTEXT_MISMATCH, "COMPATIBILITY_UNVERIFIED", true);
  }

  private StockStatus stockStatus(BigDecimal available, BigDecimal requestedQuantity) {
    if (available == null) {
      return StockStatus.UNKNOWN;
    }
    BigDecimal requested = requestedQuantity == null ? BigDecimal.ZERO : requestedQuantity;
    if (available.compareTo(requested) >= 0) {
      return StockStatus.AVAILABLE;
    }
    return available.compareTo(BigDecimal.ZERO) > 0 ? StockStatus.LOW_STOCK : StockStatus.OUT_OF_STOCK;
  }

  private static SubstituteRelationType parseRelationType(String value) {
    try {
      return SubstituteRelationType.valueOf(value);
    } catch (RuntimeException ex) {
      return SubstituteRelationType.COMPATIBLE_ALTERNATIVE;
    }
  }

  private static SubstituteRiskLevel parseRiskLevel(String value) {
    try {
      return SubstituteRiskLevel.valueOf(value);
    } catch (RuntimeException ex) {
      return SubstituteRiskLevel.MEDIUM;
    }
  }

  private static int riskRank(SubstituteRiskLevel riskLevel) {
    return switch (riskLevel) {
      case LOW -> 0;
      case MEDIUM -> 1;
      case HIGH -> 2;
    };
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public enum SubstituteRelationType {
    EXACT_REPLACEMENT,
    OEM_EQUIVALENT,
    COMPATIBLE_ALTERNATIVE,
    STOCK_PREFERRED,
    MARGIN_PREFERRED,
    CUSTOMER_ACCEPTED,
    CUSTOMER_BLOCKED
  }

  public enum SubstituteRiskLevel { LOW, MEDIUM, HIGH }
  public enum StockStatus { AVAILABLE, LOW_STOCK, OUT_OF_STOCK, UNKNOWN }
  public enum CompatibilityMatchReason { PRODUCT_RELATION_ONLY, VEHICLE_CONTEXT_MATCH, HIGH_RISK_CONFIGURED, CONTEXT_MISMATCH, NOT_CONFIGURED }

  public record SubstituteCandidate(
      UUID productId,
      String sku,
      String productName,
      SubstituteRelationType relationType,
      SubstituteRiskLevel riskLevel,
      CompatibilityMatchReason compatibilityMatchReason,
      String reasonCode,
      String matchedSource,
      BigDecimal availableStock,
      StockStatus stockStatus,
      boolean requiresApproval,
      boolean blocked,
      boolean customerAccepted,
      String explanation) {}

  private record CompatibilityDecision(CompatibilityMatchReason matchReason, String reasonCode, boolean requiresApproval) {}

  private record VehicleContext(String make, String model, Integer year) {
    static VehicleContext parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return new VehicleContext(null, null, null);
      }
      String lower = raw.toLowerCase(Locale.ROOT);
      String make = lower.contains("toyota") ? "Toyota" : null;
      String model = lower.contains("camry") ? "Camry" : null;
      Integer year = null;
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(lower);
      if (matcher.find()) {
        year = Integer.valueOf(matcher.group(1));
      }
      return new VehicleContext(make, model, year);
    }

    boolean empty() {
      return make == null && model == null && year == null;
    }

    boolean matches(String candidateMake, String candidateModel, Integer yearFrom, Integer yearTo) {
      if (make != null && (candidateMake == null || !make.equalsIgnoreCase(candidateMake))) {
        return false;
      }
      if (model != null && (candidateModel == null || !model.equalsIgnoreCase(candidateModel))) {
        return false;
      }
      if (year != null) {
        if (yearFrom != null && year < yearFrom) {
          return false;
        }
        if (yearTo != null && year > yearTo) {
          return false;
        }
      }
      return true;
    }
  }
}
