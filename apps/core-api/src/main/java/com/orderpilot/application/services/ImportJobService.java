package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.api.dto.Stage2Dtos.ImportRowRequest;
import com.orderpilot.api.dto.Stage2Dtos.ValidationError;
import com.orderpilot.api.dto.Stage2Dtos.ValidationReportResponse;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.domain.imports.ImportJobRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.imports.ImportStagingRowRepository;
import com.orderpilot.domain.imports.ImportValidationIssue;
import com.orderpilot.domain.imports.ImportValidationIssueRepository;
import com.orderpilot.domain.imports.ValidationReport;
import com.orderpilot.domain.imports.ValidationReportRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportJobService {
  private final ImportJobRepository jobRepository;
  private final ImportStagingRowRepository rowRepository;
  private final ValidationReportRepository reportRepository;
  private final ImportValidationIssueRepository issueRepository;
  private final ImportValidationService validationService;
  private final ProductRepository productRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final LocationRepository locationRepository;
  private final AuditEventService auditEventService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public ImportJobService(ImportJobRepository jobRepository, ImportStagingRowRepository rowRepository, ValidationReportRepository reportRepository, ImportValidationIssueRepository issueRepository, ImportValidationService validationService, ProductRepository productRepository, InventorySnapshotRepository inventorySnapshotRepository, LocationRepository locationRepository, AuditEventService auditEventService, JsonSupport jsonSupport, Clock clock) {
    this.jobRepository = jobRepository;
    this.rowRepository = rowRepository;
    this.reportRepository = reportRepository;
    this.issueRepository = issueRepository;
    this.validationService = validationService;
    this.productRepository = productRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.locationRepository = locationRepository;
    this.auditEventService = auditEventService;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ImportJob> list() {
    return jobRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public ImportJob get(UUID id) {
    return jobRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Import job not found"));
  }

  @Transactional
  public ImportJob create(ImportJobRequest request) {
    ImportJob job = new ImportJob(TenantContext.requireTenantId(), request.dataSourceId(), request.importType(), request.originalFilename(), request.createdBy(), clock.instant());
    ImportJob saved = jobRepository.save(job);
    auditEventService.record("import_job.created", "import_job", saved.getId().toString(), request.createdBy(), "{\"source\":\"core-api\"}");
    if (request.csvContent() != null && !request.csvContent().isBlank()) {
      int rowNumber = 1;
      for (Map<String, String> row : parseCsv(request.csvContent())) {
        rowRepository.save(new ImportStagingRow(saved.getTenantId(), saved.getId(), rowNumber++, jsonSupport.writeObject(row), clock.instant()));
      }
      saved.markStaged(rowNumber - 1, clock.instant());
      auditEventService.record("import_csv.staged", "import_job", saved.getId().toString(), request.createdBy(), "{\"source\":\"core-api\",\"format\":\"csv\"}");
    }
    return saved;
  }

  @Transactional
  public ImportStagingRow addRow(UUID jobId, ImportRowRequest request) {
    ImportJob job = get(jobId);
    ImportStagingRow row = new ImportStagingRow(job.getTenantId(), job.getId(), request.rowNumber(), request.rawData(), clock.instant());
    ImportStagingRow saved = rowRepository.save(row);
    long total = rowRepository.countByTenantIdAndImportJobId(job.getTenantId(), job.getId());
    job.markStaged((int) total, clock.instant());
    auditEventService.record("import_row.staged", "import_staging_row", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }

  @Transactional
  public ValidationReportResponse validate(UUID jobId) {
    ImportJob job = get(jobId);
    job.markValidating(clock.instant());
    issueRepository.deleteByTenantIdAndImportJobId(job.getTenantId(), job.getId());
    List<ImportStagingRow> rows = rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(job.getTenantId(), job.getId());
    Map<String, Integer> productSkuCounts = productSkuCounts(job, rows);
    int valid = 0;
    int invalid = 0;
    List<ValidationError> validationErrors = new ArrayList<>();
    for (ImportStagingRow row : rows) {
      ImportValidationService.RowValidationResult result = validationService.validate(job.getTenantId(), job.getImportType(), row);
      List<String> rowErrors = new ArrayList<>(result.validationErrors() == null ? List.of() : jsonSupport.errorMessages(result.validationErrors()));
      if ("PRODUCTS".equals(job.getImportType())) {
        String sku = text(jsonSupport.parseObject(row.getRawData()).get("sku"));
        if (!sku.isBlank() && productSkuCounts.getOrDefault(sku, 0) > 1) {
          rowErrors.add("duplicate SKU in import file");
        }
      }
      String validationStatus = rowErrors.isEmpty() ? "VALID" : "INVALID";
      row.setValidation(validationStatus, result.mappedData(), rowErrors.isEmpty() ? null : jsonSupport.errors(rowErrors));
      if ("VALID".equals(validationStatus)) {
        valid++;
      } else {
        invalid++;
        validationErrors.add(new ValidationError(row.getRowNumber(), rowErrors));
        for (String message : rowErrors) {
          issueRepository.save(new ImportValidationIssue(job.getTenantId(), job.getId(), row.getId(), row.getRowNumber(), "ERROR", issueCode(message), message, clock.instant()));
        }
      }
    }
    job.markValidated(rows.size(), valid, invalid, clock.instant());
    String summary = jsonSupport.writeObject(Map.of(
        "importJobId", job.getId(),
        "tenantId", job.getTenantId(),
        "importType", job.getImportType(),
        "totalRows", rows.size(),
        "validRows", valid,
        "invalidRows", invalid,
        "status", job.getStatus(),
        "validationErrors", validationErrors
    ));
    ValidationReport report = new ValidationReport(job.getTenantId(), job.getId(), job.getStatus(), summary, clock.instant());
    reportRepository.findByTenantIdAndImportJobId(job.getTenantId(), job.getId()).ifPresent(existing -> {
      reportRepository.delete(existing);
      reportRepository.flush();
    });
    ValidationReport saved = reportRepository.save(report);
    auditEventService.record("import_job.validated", "import_job", job.getId().toString(), null, saved.getSummary());
    return toResponse(saved, job, validationErrors);
  }

  @Transactional(readOnly = true)
  public ValidationReportResponse validationReport(UUID jobId) {
    ImportJob job = get(jobId);
    ValidationReport report = reportRepository.findByTenantIdAndImportJobId(job.getTenantId(), job.getId()).orElseThrow(() -> new NotFoundException("Validation report not found"));
    List<ValidationError> validationErrors = rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(job.getTenantId(), job.getId()).stream()
        .filter(row -> row.getValidationErrors() != null && !row.getValidationErrors().isBlank())
        .map(row -> new ValidationError(row.getRowNumber(), jsonSupport.errorMessages(row.getValidationErrors())))
        .toList();
    return toResponse(report, job, validationErrors);
  }

  @Transactional
  public ImportJob activate(UUID jobId) {
    ImportJob job = get(jobId);
    if (!"VALIDATED".equals(job.getStatus()) || job.getInvalidRows() > 0) {
      throw new IllegalArgumentException("Only validated imports with zero invalid rows can be activated in Stage 2");
    }
    List<ImportStagingRow> rows = rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(job.getTenantId(), job.getId());
    int appliedRows = switch (job.getImportType()) {
      case "PRODUCTS" -> activateProducts(job, rows);
      case "INVENTORY" -> activateInventory(job, rows);
      default -> throw new IllegalArgumentException("Activation is supported for product and inventory imports only");
    };
    job.markApplied(clock.instant());
    auditEventService.record("import_job.activated", "import_job", job.getId().toString(), null, jsonSupport.writeObject(Map.of("stage", "2", "importType", job.getImportType(), "appliedRows", appliedRows)));
    return job;
  }

  @Deprecated
  @Transactional
  public ImportJob apply(UUID jobId) {
    return activate(jobId);
  }

  @Transactional
  public ImportJob reject(UUID jobId) {
    ImportJob job = get(jobId);
    job.markRejected("Rejected by operator", clock.instant());
    auditEventService.record("import_job.rejected", "import_job", job.getId().toString(), null, "{\"source\":\"core-api\"}");
    return job;
  }

  private int activateProducts(ImportJob job, List<ImportStagingRow> rows) {
    int applied = 0;
    for (ImportStagingRow row : rows) {
      if (!"VALID".equals(row.getValidationStatus())) continue;
      Map<String, Object> data = jsonSupport.parseObject(row.getMappedData());
      String sku = text(data.get("sku"));
      if (productRepository.existsByTenantIdAndSkuAndDeletedAtIsNull(job.getTenantId(), sku)) {
        throw new IllegalArgumentException("Product SKU already exists for tenant: " + sku);
      }
      productRepository.save(new Product(job.getTenantId(), sku, text(data.get("name")), text(data.get("description")), text(data.get("category")), text(data.get("brand")), text(data.get("manufacturer")), defaultText(data.get("baseUom"), "EA"), defaultText(data.get("status"), "ACTIVE"), decimalOrNull(data.get("cost")), defaultText(data.get("currency"), "USD"), clock.instant()));
      applied++;
    }
    return applied;
  }

  private int activateInventory(ImportJob job, List<ImportStagingRow> rows) {
    int applied = 0;
    for (ImportStagingRow row : rows) {
      if (!"VALID".equals(row.getValidationStatus())) continue;
      Map<String, Object> data = jsonSupport.parseObject(row.getMappedData());
      UUID productId = resolveProductId(job.getTenantId(), data);
      UUID locationId = resolveLocationId(job.getTenantId(), data);
      BigDecimal onHand = decimalRequired(data.get("quantityOnHand"), "quantityOnHand");
      BigDecimal available = text(data.get("quantityAvailable")).isBlank() ? onHand : decimalRequired(data.get("quantityAvailable"), "quantityAvailable");
      BigDecimal reserved = text(data.get("quantityReserved")).isBlank() ? BigDecimal.ZERO : decimalRequired(data.get("quantityReserved"), "quantityReserved");
      Instant capturedAt = Instant.parse(defaultText(data.get("capturedAt"), clock.instant().toString()));
      inventorySnapshotRepository.save(new InventorySnapshot(job.getTenantId(), productId, locationId, onHand, available, reserved, capturedAt, defaultText(data.get("source"), "IMPORT"), job.getId(), clock.instant()));
      applied++;
    }
    return applied;
  }

  private UUID resolveProductId(UUID tenantId, Map<String, Object> data) {
    String productId = text(data.get("productId"));
    if (!productId.isBlank()) return UUID.fromString(productId);
    return productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, text(data.get("sku")))
        .orElseThrow(() -> new IllegalArgumentException("Product SKU was not found for tenant: " + text(data.get("sku"))))
        .getId();
  }

  private UUID resolveLocationId(UUID tenantId, Map<String, Object> data) {
    String locationId = text(data.get("locationId"));
    if (!locationId.isBlank()) return UUID.fromString(locationId);
    return locationRepository.findByTenantIdAndCode(tenantId, text(data.get("locationCode")))
        .orElseThrow(() -> new IllegalArgumentException("Location code was not found for tenant: " + text(data.get("locationCode"))))
        .getId();
  }

  private Map<String, Integer> productSkuCounts(ImportJob job, List<ImportStagingRow> rows) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (!"PRODUCTS".equals(job.getImportType())) return counts;
    for (ImportStagingRow row : rows) {
      String sku = text(jsonSupport.parseObject(row.getRawData()).get("sku"));
      if (!sku.isBlank()) counts.merge(sku, 1, Integer::sum);
    }
    return counts;
  }

  private List<Map<String, String>> parseCsv(String csvContent) {
    List<String> lines = csvContent.lines().filter(line -> !line.isBlank()).toList();
    if (lines.isEmpty()) return List.of();
    List<String> headers = splitCsvLine(lines.get(0));
    List<Map<String, String>> rows = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      List<String> values = splitCsvLine(lines.get(i));
      Map<String, String> row = new LinkedHashMap<>();
      for (int h = 0; h < headers.size(); h++) {
        row.put(headers.get(h), h < values.size() ? values.get(h) : "");
      }
      rows.add(row);
    }
    return rows;
  }

  private List<String> splitCsvLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ',' && !quoted) {
        values.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    values.add(current.toString().trim());
    return values;
  }

  private ValidationReportResponse toResponse(ValidationReport report, ImportJob job, List<ValidationError> validationErrors) {
    return new ValidationReportResponse(
        report.getId(),
        report.getImportJobId(),
        job.getTenantId(),
        job.getImportType(),
        job.getTotalRows(),
        job.getValidRows(),
        job.getInvalidRows(),
        job.getStatus(),
        validationErrors,
        report.getSummary()
    );
  }

  private String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private String defaultText(Object value, String fallback) {
    String text = text(value);
    return text.isBlank() ? fallback : text;
  }

  private BigDecimal decimalOrNull(Object value) {
    return text(value).isBlank() ? null : new BigDecimal(text(value));
  }

  private BigDecimal decimalRequired(Object value, String field) {
    if (text(value).isBlank()) throw new IllegalArgumentException(field + " is required");
    return new BigDecimal(text(value));
  }

  private String issueCode(String message) {
    return message.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
  }
}