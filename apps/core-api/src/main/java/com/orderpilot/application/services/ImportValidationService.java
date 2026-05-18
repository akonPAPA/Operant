package com.orderpilot.application.services;

import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.product.ProductRepository;
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

  public ImportValidationService(JsonSupport jsonSupport, ProductRepository productRepository, CustomerAccountRepository customerAccountRepository, LocationRepository locationRepository) {
    this.jsonSupport = jsonSupport;
    this.productRepository = productRepository;
    this.customerAccountRepository = customerAccountRepository;
    this.locationRepository = locationRepository;
  }

  public RowValidationResult validate(UUID tenantId, String importType, ImportStagingRow row) {
    Map<String, Object> raw = jsonSupport.parseObject(row.getRawData());
    Map<String, Object> mapped = new LinkedHashMap<>(raw);
    List<String> errors = switch (importType) {
      case "PRODUCTS" -> validateProduct(tenantId, mapped);
      case "CUSTOMERS" -> validateCustomer(tenantId, mapped);
      case "INVENTORY" -> validateInventory(tenantId, mapped);
      case "PRICES" -> validatePrice(mapped);
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

  private List<String> validatePrice(Map<String, Object> data) {
    List<String> errors = new ArrayList<>();
    if (text(data.get("productId")).isBlank() && text(data.get("sku")).isBlank()) errors.add("product SKU or productId is required");
    nonNegative(errors, data.get("unitPrice"), "unitPrice must be non-negative", true);
    require(errors, text(data.get("currency")), "currency is required");
    positive(errors, data.get("minQuantity"), "minQuantity must be positive");
    return errors;
  }

  private void require(List<String> errors, String value, String message) {
    if (value == null || value.isBlank()) errors.add(message);
  }

  private String text(Object value) {
    return value == null ? "" : value.toString().trim();
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

  public record RowValidationResult(String validationStatus, String mappedData, String validationErrors) {}
}