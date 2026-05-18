package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Stage2Dtos {
  private Stage2Dtos() {}

  public record ProductRequest(String sku, String name, String description, String category, String brand, String manufacturer, String baseUom, String status, BigDecimal cost, String currency) {}
  public record ProductResponse(UUID id, String sku, String name, String category, String status, String baseUom) {}
  public record ProductAliasRequest(String aliasType, String rawAlias, UUID customerAccountId, BigDecimal confidenceDefault) {}
  public record ProductAliasResponse(UUID id, UUID productId, String aliasType, String rawAlias, String normalizedAlias, boolean active) {}

  public record CustomerRequest(String externalRef, String accountCode, String legalName, String displayName, UUID segmentId, String status, String defaultCurrency, UUID defaultLocationId) {}
  public record CustomerResponse(UUID id, String accountCode, String legalName, String displayName, UUID segmentId, String status, String defaultCurrency) {}

  public record InventorySnapshotRequest(UUID productId, UUID locationId, BigDecimal quantityOnHand, BigDecimal quantityAvailable, BigDecimal quantityReserved, Instant capturedAt, String source, UUID importJobId) {}
  public record InventorySnapshotResponse(UUID id, UUID productId, UUID locationId, BigDecimal quantityOnHand, BigDecimal quantityAvailable, Instant capturedAt) {}

  public record PriceRuleRequest(UUID productId, UUID customerAccountId, UUID customerSegmentId, UUID locationId, BigDecimal minQuantity, String uom, BigDecimal unitPrice, String currency, Instant activeFrom, Instant activeTo, Integer priority) {}
  public record PriceRuleResponse(UUID id, UUID productId, BigDecimal unitPrice, String currency) {}

  public record ImportJobRequest(UUID dataSourceId, String importType, String originalFilename, UUID createdBy) {}
  public record ImportJobResponse(UUID id, String importType, String originalFilename, String status, int totalRows, int validRows, int invalidRows, String errorSummary) {}
  public record ImportRowRequest(int rowNumber, String rawData) {}
  public record ImportRowResponse(UUID id, int rowNumber, String validationStatus, String mappedData, String validationErrors) {}
  public record ValidationReportResponse(UUID id, UUID importJobId, String status, String summary) {}
}