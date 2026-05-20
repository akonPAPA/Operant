package com.orderpilot.application.services;

import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogMatchingService {
  private final ProductRepository productRepository;
  private final ProductAliasRepository aliasRepository;
  private final OEMReferenceRepository oemReferenceRepository;

  public ProductCatalogMatchingService(ProductRepository productRepository, ProductAliasRepository aliasRepository, OEMReferenceRepository oemReferenceRepository) {
    this.productRepository = productRepository;
    this.aliasRepository = aliasRepository;
    this.oemReferenceRepository = oemReferenceRepository;
  }

  @Transactional(readOnly = true)
  public ProductCatalogMatchResult match(UUID tenantId, String rawCode, String rawText, UUID customerAccountId) {
    String normalized = ProductCodeNormalizer.normalize(rawCode);
    if (!normalized.isBlank()) {
      ProductCatalogMatchResult skuMatch = resolveProducts(tenantId, normalized, productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, normalized, "ACTIVE"), ProductMatchType.SKU_EXACT, new BigDecimal("1.00"));
      if (skuMatch.matched()) {
        return skuMatch;
      }

      List<ProductAlias> customerAliases = customerAccountId == null ? List.of() : aliasRepository.findByTenantIdAndNormalizedAliasAndCustomerAccountIdAndActiveTrue(tenantId, normalized, customerAccountId);
      ProductCatalogMatchResult customerAliasMatch = resolveAliasProducts(tenantId, normalized, customerAliases, ProductMatchType.ALIAS_EXACT, new BigDecimal("0.95"));
      if (customerAliasMatch.matched()) {
        return customerAliasMatch;
      }

      List<ProductAlias> globalAliases = aliasRepository.findByTenantIdAndNormalizedAliasAndCustomerAccountIdIsNullAndActiveTrue(tenantId, normalized);
      ProductCatalogMatchResult aliasMatch = resolveAliasProducts(tenantId, normalized, globalAliases, ProductMatchType.ALIAS_EXACT, new BigDecimal("0.95"));
      if (aliasMatch.matched()) {
        return aliasMatch;
      }

      List<OEMReference> oemMatches = oemReferenceRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, normalized);
      ProductCatalogMatchResult oemMatch = resolveOemProducts(tenantId, normalized, oemMatches);
      if (oemMatch.matched()) {
        return oemMatch;
      }
    }

    return new ProductCatalogMatchResult(ProductMatchType.NO_MATCH, null, normalized, null, null, BigDecimal.ZERO, List.of(), true);
  }

  private ProductCatalogMatchResult resolveAliasProducts(UUID tenantId, String normalized, List<ProductAlias> aliases, ProductMatchType type, BigDecimal confidence) {
    return resolveProductIds(tenantId, normalized, aliases.stream().map(ProductAlias::getProductId).toList(), type, confidence);
  }

  private ProductCatalogMatchResult resolveOemProducts(UUID tenantId, String normalized, List<OEMReference> references) {
    return resolveProductIds(tenantId, normalized, references.stream().map(OEMReference::getProductId).toList(), ProductMatchType.OEM_EXACT, new BigDecimal("0.90"));
  }

  private ProductCatalogMatchResult resolveProductIds(UUID tenantId, String normalized, List<UUID> productIds, ProductMatchType type, BigDecimal confidence) {
    Set<UUID> distinctIds = new LinkedHashSet<>(productIds);
    List<Product> products = distinctIds.stream()
        .map(id -> productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId))
        .flatMap(java.util.Optional::stream)
        .filter(product -> "ACTIVE".equals(product.getStatus()))
        .toList();
    return resolveProducts(tenantId, normalized, products, type, confidence);
  }

  private ProductCatalogMatchResult resolveProducts(UUID tenantId, String normalized, List<Product> products, ProductMatchType type, BigDecimal confidence) {
    if (products.isEmpty()) {
      return new ProductCatalogMatchResult(ProductMatchType.NO_MATCH, null, normalized, null, null, BigDecimal.ZERO, List.of(), true);
    }
    if (products.size() > 1) {
      return new ProductCatalogMatchResult(ProductMatchType.AMBIGUOUS, null, normalized, null, null, new BigDecimal("0.40"), products.stream().map(Product::getId).toList(), true);
    }
    Product product = products.get(0);
    return new ProductCatalogMatchResult(type, product.getId(), normalized, product.getSku(), product.getName(), confidence, List.of(product.getId()), false);
  }

  public enum ProductMatchType {
    SKU_EXACT,
    ALIAS_EXACT,
    OEM_EXACT,
    NAME_TEXT_WEAK,
    NO_MATCH,
    AMBIGUOUS
  }

  public record ProductCatalogMatchResult(
      ProductMatchType matchType,
      UUID productId,
      String normalizedCode,
      String matchedSku,
      String productName,
      BigDecimal confidence,
      List<UUID> candidateProductIds,
      boolean requiresReview) {
    public boolean matched() {
      return productId != null || matchType == ProductMatchType.AMBIGUOUS;
    }
  }
}
