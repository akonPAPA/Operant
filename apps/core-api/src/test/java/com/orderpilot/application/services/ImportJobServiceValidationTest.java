package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage2Dtos.ValidationReportResponse;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.domain.imports.ImportJobRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.imports.ImportStagingRowRepository;
import com.orderpilot.domain.imports.ImportValidationIssue;
import com.orderpilot.domain.imports.ImportValidationIssueRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.imports.ValidationReport;
import com.orderpilot.domain.imports.ValidationReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImportJobServiceValidationTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC);

  private final ImportJobRepository jobRepository = mock(ImportJobRepository.class);
  private final ImportStagingRowRepository rowRepository = mock(ImportStagingRowRepository.class);
  private final ValidationReportRepository reportRepository = mock(ValidationReportRepository.class);
  private final ImportValidationIssueRepository issueRepository = mock(ImportValidationIssueRepository.class);
  private final ImportValidationService validationService = mock(ImportValidationService.class);
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final InventorySnapshotRepository inventorySnapshotRepository = mock(InventorySnapshotRepository.class);
  private final LocationRepository locationRepository = mock(LocationRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final JsonSupport jsonSupport = new JsonSupport(new ObjectMapper());
  private final ImportJobService service = new ImportJobService(jobRepository, rowRepository, reportRepository, issueRepository, validationService, productRepository, inventorySnapshotRepository, locationRepository, auditEventService, jsonSupport, CLOCK);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validatesAllRowsAndMarksJobValidated() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ImportJob job = job(tenantId, "PRODUCTS");
    ImportStagingRow row1 = row(tenantId, job.getId(), 1, "{\"sku\":\"P-1\",\"name\":\"Pad\",\"baseUom\":\"EA\"}");
    ImportStagingRow row2 = row(tenantId, job.getId(), 2, "{\"sku\":\"P-2\",\"name\":\"Rotor\",\"baseUom\":\"EA\"}");
    when(jobRepository.findByIdAndTenantId(job.getId(), tenantId)).thenReturn(Optional.of(job));
    when(rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(tenantId, job.getId())).thenReturn(List.of(row1, row2));
    when(validationService.validate(eq(tenantId), eq("PRODUCTS"), any(ImportStagingRow.class)))
        .thenReturn(new ImportValidationService.RowValidationResult("VALID", "{}", null));
    when(reportRepository.save(any(ValidationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(issueRepository.save(any(ImportValidationIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));

    ValidationReportResponse report = service.validate(job.getId());

    assertThat(job.getStatus()).isEqualTo("VALIDATED");
    assertThat(job.getTotalRows()).isEqualTo(2);
    assertThat(report.validRows()).isEqualTo(2);
    assertThat(report.invalidRows()).isZero();
    assertThat(report.validationErrors()).isEmpty();
    assertThat(row1.getValidationStatus()).isEqualTo("VALID");
    assertThat(row2.getValidationStatus()).isEqualTo("VALID");
  }

  @Test
  void validatesMixedRowsAndKeepsJobValidatedWithInvalidCount() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ImportJob job = job(tenantId, "PRODUCTS");
    ImportStagingRow validRow = row(tenantId, job.getId(), 1, "{\"sku\":\"P-1\",\"name\":\"Pad\",\"baseUom\":\"EA\"}");
    ImportStagingRow invalidRow = row(tenantId, job.getId(), 2, "{\"sku\":\"P-2\"}");
    when(jobRepository.findByIdAndTenantId(job.getId(), tenantId)).thenReturn(Optional.of(job));
    when(rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(tenantId, job.getId())).thenReturn(List.of(validRow, invalidRow));
    when(validationService.validate(tenantId, "PRODUCTS", validRow))
        .thenReturn(new ImportValidationService.RowValidationResult("VALID", "{}", null));
    when(validationService.validate(tenantId, "PRODUCTS", invalidRow))
        .thenReturn(new ImportValidationService.RowValidationResult("INVALID", "{}", jsonSupport.errors(List.of("name is required"))));
    when(reportRepository.save(any(ValidationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(issueRepository.save(any(ImportValidationIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));

    ValidationReportResponse report = service.validate(job.getId());

    assertThat(job.getStatus()).isEqualTo("VALIDATED");
    assertThat(job.getInvalidRows()).isEqualTo(1);
    assertThat(job.getErrorSummary()).contains("1 row");
    assertThat(report.validationErrors()).singleElement()
        .satisfies(error -> {
          assertThat(error.rowNumber()).isEqualTo(2);
          assertThat(error.errors()).containsExactly("name is required");
        });
    assertThatThrownBy(() -> service.activate(job.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero invalid rows");
  }

  @Test
  void missingJobThrowsNotFound() {
    UUID tenantId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(jobRepository.findByIdAndTenantId(jobId, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.validate(jobId))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Import job not found");
  }

  @Test
  void noRowsProducesEmptyValidatedReport() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ImportJob job = job(tenantId, "PRODUCTS");
    when(jobRepository.findByIdAndTenantId(job.getId(), tenantId)).thenReturn(Optional.of(job));
    when(rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(tenantId, job.getId())).thenReturn(List.of());
    when(reportRepository.save(any(ValidationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(issueRepository.save(any(ImportValidationIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));

    ValidationReportResponse report = service.validate(job.getId());

    assertThat(job.getStatus()).isEqualTo("VALIDATED");
    assertThat(report.totalRows()).isZero();
    assertThat(report.validRows()).isZero();
    assertThat(report.invalidRows()).isZero();
    assertThat(report.validationErrors()).isEmpty();
  }

  private ImportJob job(UUID tenantId, String importType) {
    ImportJob job = new ImportJob(tenantId, UUID.randomUUID(), importType, "import.json", UUID.randomUUID(), CLOCK.instant());
    ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
    return job;
  }

  private ImportStagingRow row(UUID tenantId, UUID jobId, int rowNumber, String rawData) {
    return new ImportStagingRow(tenantId, jobId, rowNumber, rawData, CLOCK.instant());
  }
}
