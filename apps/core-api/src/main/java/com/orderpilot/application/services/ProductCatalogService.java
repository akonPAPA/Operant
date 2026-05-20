package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.ProductAliasRequest;
import com.orderpilot.api.dto.Stage2Dtos.ProductMatchResponse;
import com.orderpilot.api.dto.Stage2Dtos.ProductRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogService {
  private final ProductRepository productRepository;
  private final ProductAliasRepository aliasRepository;
  private final ProductCatalogMatchingService matchingService;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ProductCatalogService(ProductRepository productRepository, ProductAliasRepository aliasRepository, ProductCatalogMatchingService matchingService, AuditEventService auditEventService, Clock clock) {
    this.productRepository = productRepository;
    this.aliasRepository = aliasRepository;
    this.matchingService = matchingService;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Product> list() {
    return productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public Product get(UUID id) {
    return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.requireTenantId())
        .orElseThrow(() -> new IllegalArgumentException("Product not found"));
  }

  @Transactional(readOnly = true)
  public List<Product> search(String query) {
    UUID tenantId = TenantContext.requireTenantId();
    String term = query == null ? "" : query;
    return productRepository.findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(tenantId, term, tenantId, term);
  }

  @Transactional(readOnly = true)
  public ProductMatchResponse match(String code, UUID customerAccountId) {
    var result = matchingService.match(TenantContext.requireTenantId(), code, null, customerAccountId);
    return new ProductMatchResponse(result.matchType().name(), result.productId(), result.normalizedCode(), result.matchedSku(), result.productName(), result.confidence(), result.candidateProductIds(), result.requiresReview());
  }

  @Transactional
  public Product create(ProductRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    if (productRepository.existsByTenantIdAndSkuAndDeletedAtIsNull(tenantId, request.sku())) {
      throw new IllegalArgumentException("Product SKU already exists for tenant");
    }
    Product product = new Product(tenantId, request.sku(), request.name(), request.description(), request.category(), request.brand(), request.manufacturer(), request.baseUom(), request.status(), request.cost(), request.currency(), clock.instant());
    Product saved = productRepository.save(product);
    auditEventService.record("product.created", "product", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }

  @Transactional
  public Product update(UUID id, ProductRequest request) {
    Product product = get(id);
    product.update(request.name(), request.description(), request.category(), request.brand(), request.manufacturer(), request.baseUom(), request.status(), request.cost(), request.currency(), clock.instant());
    auditEventService.record("product.updated", "product", id.toString(), null, "{\"source\":\"core-api\"}");
    return product;
  }

  @Transactional(readOnly = true)
  public List<ProductAlias> listAliases(UUID productId) {
    Product product = get(productId);
    return aliasRepository.findByTenantIdAndProductIdAndActiveTrueOrderByRawAlias(product.getTenantId(), product.getId());
  }

  @Transactional
  public ProductAlias addAlias(UUID productId, ProductAliasRequest request) {
    Product product = get(productId);
    ProductAlias alias = new ProductAlias(product.getTenantId(), product.getId(), request.aliasType(), request.rawAlias(), normalize(request.rawAlias()), request.customerAccountId(), request.confidenceDefault(), clock.instant());
    ProductAlias saved = aliasRepository.save(alias);
    auditEventService.record("product_alias.created", "product_alias", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }

  public String normalize(String value) {
    return ProductCodeNormalizer.normalize(value);
  }
}
