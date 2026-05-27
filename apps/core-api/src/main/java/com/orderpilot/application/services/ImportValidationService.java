package com.orderpilot.application.services;

import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ImportValidationService {
  private final JsonSupport jsonSupport;
  private final ProductRepository productRepository;
  private final CustomerAccountRepository customerAccountRepository;
  private final LocationRepository locationRepository;
  private final ProductAliasRepository productAliasRepository;
  private final OEMReferenceRepository oemReferenceRepository;
  private final ProductSubstituteRepository productSubstituteRepository;
  private final ProductCompatibilityRepository productCompatibilityRepository;
  private final PriceRuleRepository priceRuleRepository;
  private final DiscountRuleRepository discountRuleRepository;
  private final MarginRuleRepository marginRuleRepository;

  public ImportValidationService(JsonSupport jsonSupport, ProductRepository productRepository, CustomerAccountRepository customerAccountRepository, LocationRepository locationRepository, ProductAliasRepository productAliasRepository, OEMReferenceRepository oemReferenceRepository, ProductSubstituteRepository productSubstituteRepository, ProductCompatibilityRepository productCompatibilityRepository, PriceRuleRepository priceRuleRepository, DiscountRuleRepository discountRuleRepository, MarginRuleRepository marginRuleRepository) {
    this.jsonSupport = jsonSupport;
    this.productRepository = productRepository;
    this.customerAccountRepository = customerAccountRepository;
    this.locationRepository = locationRepository;
    this.productAliasRepository = productAliasRepository;
    this.oemReferenceRepository = oemReferenceRepository;
    this.productSubstituteRepository = productSubstituteRepository;
    this.productCompatibilityRepository = productCompatibilityRepository;
    this.priceRuleRepository = priceRuleRepository;
    this.discountRuleRepository = discountRuleRepository;
    this.marginRuleRepository = marginRuleRepository;
  }

  public RowValidationResult validate(UUID tenantId, String importType, ImportStagingRow row) {
    Map<String, Object> raw = jsonSupport.parseObject(row.getRawData());
    Map<String, Object> mapped = new LinkedHashMap<>(raw);
    List<String> errors = switch (importType) {
      case "PRODUCTS" -> validateProduct(tenantId, mapped);
      case "CUSTOMERS" -> validateCustomer(tenantId, mapped);
      case "INVENTORY" -> validateInventory(tenantId, mapped);
      case "LOCATIONS" -> validateLocation(tenantId, mapped);
      case "PRODUCT_ALIASES" -> validateProductAlias(tenantId, mapped);
      case "OEM_REFERENCES" -> validateOemReference(tenantId, mapped);
      case "PRICE_RULES", "PRICES" -> validatePrice(tenantId, mapped);
      case "PRODUCT_SUBSTITUTES", "SUBSTITUTES" -> validateProductSubstitute(tenantId, mapped);
      case "COMPATIBILITY", "PRODUCT_COMPATIBILITY" -> validateCompatibility(tenantId, mapped);
      case "DISCOUNT_RULES" -> validateDiscountRule(tenantId, mapped);
      case "MARGIN_RULES" -> validateMarginRule(tenantId, mapped);
      default -> List.of("Unsupported import type for Stage 2 validation: " + importType);
    };
    String status = errors.isEmpty() ? "VALID" : "INVALID";
    return new RowValidationResult(status, jsonSupport.writeObject(mapped), errors.isEmpty() ? null : jsonSupport.errors(errors));
  }

  private List<String> validateProduct(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String sku = text(data.get("sku"));
    require(errors, sku, "sku is required");
    require(errors, text(data.get("name")), "name is required");
    require(errors, text(data.get("baseUom")), "baseUom is required");
    if (!sku.isBlank() && productRepository.existsByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku)) {
      errors.add("duplicate SKU exists for tenant");
    }
    nonNegative(errors, data.get("cost"), "cost must be non-negative if present", false);
    return errors;
  }

  private List<String> validateCustomer(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String accountCode = text(data.get("accountCode"));
    require(errors, accountCode, "accountCode is required");
    if (text(data.get("legalName")).isBlank() && text(data.get("displayName")).isBlank()) {
      errors.add("legalName or displayName is required");
    }
    if (!accountCode.isBlank() && customerAccountRepository.existsByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, accountCode)) {
      errors.add("duplicate accountCode exists for tenant");
    }
    return errors;
  }

  private List<String> validateInventory(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String productId = text(data.get("productId"));
    String sku = text(data.get("sku"));
    String locationId = text(data.get("locationId"));
    String locationCode = text(data.get("locationCode"));
    if (productId.isBlank() && sku.isBlank()) errors.add("product SKU or productId is required");
    if (locationId.isBlank() && locationCode.isBlank()) errors.add("location code or locationId is required");
    if (!sku.isBlank() && productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku).isEmpty()) errors.add("product SKU was not found for tenant");
    if (!locationCode.isBlank() && locationRepository.findByTenantIdAndCode(tenantId, locationCode).isEmpty()) errors.add("location code was not found for tenant");
    nonNegative(errors, data.get("quantityOnHand"), "quantityOnHand must be numeric and non-negative", true);
    String capturedAt = text(data.get("capturedAt"));
    if (capturedAt.isBlank()) {
      data.put("capturedAt", Instant.now().toString());
    } else {
      try {
        Instant.parse(capturedAt);
      } catch (DateTimeParseException ex) {
        errors.add("capturedAt must be an ISO-8601 timestamp");
      }
    }
    return errors;
  }

  private List<String> validateLocation(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String code = text(data.get("code"));
    require(errors, code, "code is required");
    require(errors, text(data.get("name")), "name is required");
    require(errors, text(data.get("type")), "type is required");
    if (!code.isBlank() && locationRepository.findByTenantIdAndCode(tenantId, code).isPresent()) errors.add("duplicate location code exists for tenant");
    return errors;
  }

  private List<String> validateProductAlias(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String sku = text(data.get("sku"));
    String rawAlias = text(data.get("rawAlias"));
    require(errors, sku, "sku is required");
    require(errors, rawAlias, "rawAlias is required");
    productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku).ifPresentOrElse(product -> {
      String normalized = ProductCodeNormalizer.normalize(rawAlias);
      data.put("normalizedAlias", normalized);
      data.put("productId", product.getId().toString());
      if (productAliasRepository.existsByTenantIdAndProductIdAndNormalizedAliasAndActiveTrue(tenantId, product.getId(), normalized)) {
        errors.add("duplicate product alias exists for tenant");
      }
    }, () -> {
      if (!sku.isBlank()) errors.add("product SKU was not found for tenant");
    });
    return errors;
  }

  private List<String> validateOemReference(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String sku = text(data.get("sku"));
    String oemCode = text(data.get("oemCode"));
    require(errors, sku, "sku is required");
    require(errors, oemCode, "oemCode is required");
    productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku).ifPresentOrElse(product -> {
      String normalized = ProductCodeNormalizer.normalize(oemCode);
      data.put("normalizedOemCode", normalized);
      data.put("productId", product.getId().toString());
      if (oemReferenceRepository.existsByTenantIdAndProductIdAndNormalizedOemCodeAndActiveTrue(tenantId, product.getId(), normalized)) {
        errors.add("duplicate OEM reference exists for tenant");
      }
    }, () -> {
      if (!sku.isBlank()) errors.add("product SKU was not found for tenant");
    });
    return errors;
  }

  private List<String> validatePrice(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    UUID productId = resolveProduct(tenantId, data, errors);
    UUID customerId = resolveCustomer(tenantId, data, errors);
    nonNegative(errors, data.get("unitPrice"), "unitPrice must be non-negative", true);
    require(errors, text(data.get("currency")), "currency is required");
    positive(errors, data.get("minQuantity"), "minQuantity must be positive");
    instant(errors, data.get("activeFrom"), "activeFrom must be an ISO-8601 timestamp", true);
    if (!text(data.get("activeTo")).isBlank()) instant(errors, data.get("activeTo"), "activeTo must be an ISO-8601 timestamp", false);
    if (productId != null && text(data.get("unitPrice")).matches("-?\\d+(\\.\\d+)?") && priceRuleRepository.existsByTenantIdAndProductIdAndCustomerAccountIdAndUnitPriceAndActiveTrue(tenantId, productId, customerId, new BigDecimal(text(data.get("unitPrice"))))) {
      errors.add("duplicate price rule exists for tenant");
    }
    return errors;
  }

  private List<String> validateProductSubstitute(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    UUID sourceProductId = resolveProductBySkuField(tenantId, data, "sourceSku", "source product SKU was not found for tenant", errors);
    UUID substituteProductId = resolveProductBySkuField(tenantId, data, "substituteSku", "substitute product SKU was not found for tenant", errors);
    require(errors, text(data.get("substituteType")), "substituteType is required");
    if (sourceProductId != null && sourceProductId.equals(substituteProductId)) errors.add("source and substitute products must differ");
    if (sourceProductId != null && substituteProductId != null && productSubstituteRepository.existsByTenantIdAndSourceProductIdAndSubstituteProductIdAndSubstituteTypeAndActiveTrue(tenantId, sourceProductId, substituteProductId, defaultText(data.get("substituteType"), "AFTERMARKET"))) {
      errors.add("duplicate product substitute exists for tenant");
    }
    return errors;
  }

  private List<String> validateCompatibility(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    UUID productId = resolveProduct(tenantId, data, errors);
    require(errors, text(data.get("make")), "make is required");
    require(errors, text(data.get("model")), "model is required");
    integer(errors, data.get("yearFrom"), "yearFrom must be an integer", true);
    integer(errors, data.get("yearTo"), "yearTo must be an integer", true);
    if (productId != null && productCompatibilityRepository.existsByTenantIdAndProductIdAndMakeAndModelAndYearFromAndYearToAndActiveTrue(tenantId, productId, text(data.get("make")), text(data.get("model")), intValue(data.get("yearFrom")), intValue(data.get("yearTo")))) {
      errors.add("duplicate compatibility row exists for tenant");
    }
    return errors;
  }

  private List<String> validateDiscountRule(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String code = text(data.get("code"));
    require(errors, code, "code is required");
    require(errors, text(data.get("name")), "name is required");
    resolveOptionalProduct(tenantId, data, errors);
    resolveCustomer(tenantId, data, errors);
    nonNegative(errors, data.get("maxDiscountPercent"), "maxDiscountPercent must be non-negative", true);
    nonNegative(errors, data.get("requiresApprovalAbovePercent"), "requiresApprovalAbovePercent must be non-negative", true);
    instant(errors, data.get("activeFrom"), "activeFrom must be an ISO-8601 timestamp", true);
    if (!code.isBlank() && discountRuleRepository.existsByTenantIdAndCodeAndActiveTrue(tenantId, code)) errors.add("duplicate discount rule code exists for tenant");
    return errors;
  }

  private List<String> validateMarginRule(UUID tenantId, Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    String code = text(data.get("code"));
    require(errors, code, "code is required");
    require(errors, text(data.get("name")), "name is required");
    resolveOptionalProduct(tenantId, data, errors);
    nonNegative(errors, data.get("minimumGrossMarginPercent"), "minimumGrossMarginPercent must be non-negative", true);
    nonNegative(errors, data.get("approvalRequiredBelowPercent"), "approvalRequiredBelowPercent must be non-negative", true);
    if (!code.isBlank() && marginRuleRepository.existsByTenantIdAndCodeAndActiveTrue(tenantId, code)) errors.add("duplicate margin rule code exists for tenant");
    return errors;
  }

  private void require(List<String> errors, String value, String message) {
    if (value == null || value.isBlank()) errors.add(message);
  }

  private String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private String defaultText(Object value, String fallback) {
    String text = text(value);
    return text.isBlank() ? fallback : text;
  }

  private UUID resolveProduct(UUID tenantId, Map<String, Object> data, List<String> errors) {
    return resolveProductBySkuField(tenantId, data, "sku", "product SKU was not found for tenant", errors);
  }

  private UUID resolveOptionalProduct(UUID tenantId, Map<String, Object> data, List<String> errors) {
    if (text(data.get("sku")).isBlank() && text(data.get("productId")).isBlank()) return null;
    return resolveProduct(tenantId, data, errors);
  }

  private UUID resolveProductBySkuField(UUID tenantId, Map<String, Object> data, String skuField, String missingMessage, List<String> errors) {
    String productId = text(data.get(skuField.equals("sku") ? "productId" : skuField.replace("Sku", "ProductId")));
    if (!productId.isBlank()) {
      try {
        UUID id = UUID.fromString(productId);
        data.put(skuField.equals("sku") ? "productId" : skuField.replace("Sku", "ProductId"), id.toString());
        return id;
      } catch (IllegalArgumentException ex) {
        errors.add((skuField.equals("sku") ? "productId" : skuField.replace("Sku", "ProductId")) + " must be a UUID");
        return null;
      }
    }
    String sku = text(data.get(skuField));
    if (sku.isBlank()) {
      errors.add(skuField + " is required");
      return null;
    }
    return productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, sku)
        .map(product -> {
          data.put(skuField.equals("sku") ? "productId" : skuField.replace("Sku", "ProductId"), product.getId().toString());
          return product.getId();
        })
        .orElseGet(() -> {
          errors.add(missingMessage);
          return null;
        });
  }

  private UUID resolveCustomer(UUID tenantId, Map<String, Object> data, List<String> errors) {
    String customerId = text(data.get("customerAccountId"));
    if (!customerId.isBlank()) {
      try {
        return UUID.fromString(customerId);
      } catch (IllegalArgumentException ex) {
        errors.add("customerAccountId must be a UUID");
        return null;
      }
    }
    String accountCode = text(data.get("accountCode"));
    if (accountCode.isBlank()) return null;
    return customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, accountCode)
        .map(customer -> {
          data.put("customerAccountId", customer.getId().toString());
          return customer.getId();
        })
        .orElseGet(() -> {
          errors.add("customer accountCode was not found for tenant");
          return null;
        });
  }

  private void nonNegative(List<String> errors, Object value, String message, boolean required) {
    if (value == null || text(value).isBlank()) {
      if (required) errors.add(message);
      return;
    }
    try {
      if (new BigDecimal(value.toString()).compareTo(BigDecimal.ZERO) < 0) errors.add(message);
    } catch (NumberFormatException ex) {
      errors.add(message);
    }
  }

  private void positive(List<String> errors, Object value, String message) {
    if (value == null || text(value).isBlank()) {
      errors.add(message);
      return;
    }
    try {
      if (new BigDecimal(value.toString()).compareTo(BigDecimal.ZERO) <= 0) errors.add(message);
    } catch (NumberFormatException ex) {
      errors.add(message);
    }
  }

  private void integer(List<String> errors, Object value, String message, boolean required) {
    if (value == null || text(value).isBlank()) {
      if (required) errors.add(message);
      return;
    }
    try {
      Integer.parseInt(value.toString());
    } catch (NumberFormatException ex) {
      errors.add(message);
    }
  }

  private Integer intValue(Object value) {
    return text(value).isBlank() ? null : Integer.parseInt(text(value));
  }

  private void instant(List<String> errors, Object value, String message, boolean required) {
    if (value == null || text(value).isBlank()) {
      if (required) errors.add(message);
      return;
    }
    try {
      Instant.parse(value.toString());
    } catch (DateTimeParseException ex) {
      errors.add(message);
    }
  }

  public record RowValidationResult(String validationStatus, String mappedData, String validationErrors) {}
}
