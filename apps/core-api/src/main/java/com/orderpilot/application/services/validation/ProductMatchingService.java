package com.orderpilot.application.services.validation;

import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.product.*;
import com.orderpilot.domain.validation.ProductMatchResult;
import com.orderpilot.domain.validation.ProductMatchResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductMatchingService {
  private final ProductRepository productRepository; private final ProductAliasRepository aliasRepository; private final OEMReferenceRepository oemRepository; private final ProductMatchResultRepository resultRepository; private final ValidationIssueService issueService; private final ApprovalRequirementService approvalService; private final JsonSupport jsonSupport; private final Clock clock;
  public ProductMatchingService(ProductRepository productRepository, ProductAliasRepository aliasRepository, OEMReferenceRepository oemRepository, ProductMatchResultRepository resultRepository, ValidationIssueService issueService, ApprovalRequirementService approvalService, JsonSupport jsonSupport, Clock clock) { this.productRepository=productRepository; this.aliasRepository=aliasRepository; this.oemRepository=oemRepository; this.resultRepository=resultRepository; this.issueService=issueService; this.approvalService=approvalService; this.jsonSupport=jsonSupport; this.clock=clock; }

  @Transactional
  public ProductMatchResult match(UUID validationRunId, UUID extractionResultId, ExtractedLineItem line) {
    UUID tenantId = TenantContext.requireTenantId();
    String sku = normalizeCode(line.getRawSku());
    if (!sku.isBlank()) {
      var exact = productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku);
      if (exact.isPresent()) return save(tenantId, validationRunId, line, exact.get().getId(), "EXACT_SKU", "MATCHED", "0.9900", null);
      List<ProductAlias> aliases = aliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, sku);
      if (aliases.size() == 1) { issueService.open(validationRunId, extractionResultId, line.getId(), null, "PRODUCT_ALIAS_MATCHED", "INFO", "Product matched through a tenant alias", "{}"); return save(tenantId, validationRunId, line, aliases.get(0).getProductId(), "PRODUCT_ALIAS", "MATCHED", "0.9000", candidates(aliases.stream().map(ProductAlias::getProductId).toList())); }
      if (aliases.size() > 1) return ambiguous(validationRunId, extractionResultId, tenantId, line, "PRODUCT_ALIAS", candidates(aliases.stream().map(ProductAlias::getProductId).toList()));
      List<OEMReference> oems = oemRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, sku);
      if (oems.size() == 1) { issueService.open(validationRunId, extractionResultId, line.getId(), null, "OEM_MATCHED", "INFO", "Product matched through OEM reference", "{}"); return save(tenantId, validationRunId, line, oems.get(0).getProductId(), "OEM_REFERENCE", "MATCHED", "0.8800", candidates(oems.stream().map(OEMReference::getProductId).toList())); }
      if (oems.size() > 1) return ambiguous(validationRunId, extractionResultId, tenantId, line, "OEM_REFERENCE", candidates(oems.stream().map(OEMReference::getProductId).toList()));
    }
    if (line.getRawDescription() != null && !line.getRawDescription().isBlank()) {
      List<Product> descriptionMatches = productRepository.findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(tenantId, line.getRawDescription(), tenantId, line.getRawDescription());
      if (descriptionMatches.size() == 1) return save(tenantId, validationRunId, line, descriptionMatches.get(0).getId(), "DESCRIPTION_SEARCH", "NEEDS_REVIEW", "0.6500", candidates(descriptionMatches.stream().map(Product::getId).toList()));
      if (descriptionMatches.size() > 1) return ambiguous(validationRunId, extractionResultId, tenantId, line, "DESCRIPTION_SEARCH", candidates(descriptionMatches.stream().map(Product::getId).limit(5).toList()));
    }
    issueService.open(validationRunId, extractionResultId, line.getId(), null, "PRODUCT_NOT_FOUND", "ERROR", "No deterministic product match was found", "{\"rawSku\":\"" + (line.getRawSku() == null ? "" : line.getRawSku()) + "\"}");
    return save(tenantId, validationRunId, line, null, "NONE", "NOT_FOUND", "0.0000", null);
  }

  @Transactional(readOnly = true)
  public List<ProductMatchResult> list(UUID validationRunId) { return resultRepository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId); }

  private ProductMatchResult ambiguous(UUID runId, UUID extractionResultId, UUID tenantId, ExtractedLineItem line, String matchType, String candidatesJson) {
    issueService.open(runId, extractionResultId, line.getId(), null, "PRODUCT_AMBIGUOUS", "ERROR", "Product lookup returned multiple candidates", candidatesJson);
    approvalService.create(runId, line.getId(), "PRODUCT_AMBIGUOUS", "HIGH", "Operator must choose the correct product");
    return save(tenantId, runId, line, null, matchType, "AMBIGUOUS", "0.5000", candidatesJson);
  }

  private ProductMatchResult save(UUID tenantId, UUID runId, ExtractedLineItem line, UUID productId, String matchType, String status, String confidence, String candidatesJson) {
    return resultRepository.save(new ProductMatchResult(tenantId, runId, line.getId(), productId, line.getRawSku(), line.getRawDescription(), matchType, new BigDecimal(confidence), status, candidatesJson, clock.instant()));
  }

  private String candidates(List<UUID> ids) { return jsonSupport.writeObject(Map.of("candidateProductIds", ids)); }
  private String normalizeCode(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); }
}
