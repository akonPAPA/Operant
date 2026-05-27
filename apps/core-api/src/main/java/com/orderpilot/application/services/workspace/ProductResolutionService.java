package com.orderpilot.application.services.workspace;

import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCatalogMatchingService.ProductCatalogMatchResult;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductResolutionService {
  private final ProductCatalogMatchingService matchingService;
  private final ProductRepository productRepository;

  public ProductResolutionService(ProductCatalogMatchingService matchingService, ProductRepository productRepository) {
    this.matchingService = matchingService;
    this.productRepository = productRepository;
  }

  @Transactional(readOnly = true)
  public ProductResolution resolve(UUID tenantId, String rawSkuOrAlias, String description, UUID customerAccountId) {
    ProductCatalogMatchResult match = matchingService.match(tenantId, firstNonBlank(rawSkuOrAlias, description), description, customerAccountId);
    Optional<Product> product = match.productId() == null ? Optional.empty() : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(match.productId(), tenantId);
    return new ProductResolution(product.orElse(null), match);
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

  public record ProductResolution(Product product, ProductCatalogMatchResult match) {}
}
