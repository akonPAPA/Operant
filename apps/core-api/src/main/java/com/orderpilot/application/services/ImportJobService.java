package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.api.dto.Stage2Dtos.ImportRowRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.domain.imports.ImportJobRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.imports.ImportStagingRowRepository;
import com.orderpilot.domain.imports.ValidationReport;
import com.orderpilot.domain.imports.ValidationReportRepository;
import java.time.Clock;
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
  private final ImportValidationService validationService;
  private final AuditEventService auditEventService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public ImportJobService(ImportJobRepository jobRepository, ImportStagingRowRepository rowRepository, ValidationReportRepository reportRepository, ImportValidationService validationService, AuditEventService auditEventService, JsonSupport jsonSupport, Clock clock) {
    this.jobRepository = jobRepository;
    this.rowRepository = rowRepository;
    this.reportRepository = reportRepository;
    this.validationService = validationService;
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
    return jobRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Import job not found"));
  }

  @Transactional
  public ImportJob create(ImportJobRequest request) {
    ImportJob job = new ImportJob(TenantContext.requireTenantId(), request.dataSourceId(), request.importType(), request.originalFilename(), request.createdBy(), clock.instant());
    ImportJob saved = jobRepository.save(job);
    auditEventService.record("import_job.created", "import_job", saved.getId().toString(), request.createdBy(), "{\"source\":\"core-api\"}");
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
  public ValidationReport validate(UUID jobId) {
    ImportJob job = get(jobId);
    List<ImportStagingRow> rows = rowRepository.findByTenantIdAndImportJobIdOrderByRowNumber(job.getTenantId(), job.getId());
    int valid = 0;
    int invalid = 0;
    for (ImportStagingRow row : rows) {
      ImportValidationService.RowValidationResult result = validationService.validate(job.getTenantId(), job.getImportType(), row);
      row.setValidation(result.validationStatus(), result.mappedData(), result.validationErrors());
      if ("VALID".equals(result.validationStatus())) valid++; else invalid++;
    }
    job.markValidated(valid, invalid, clock.instant());
    ValidationReport report = new ValidationReport(job.getTenantId(), job.getId(), job.getStatus(), jsonSupport.writeObject(Map.of("totalRows", rows.size(), "validRows", valid, "invalidRows", invalid, "importType", job.getImportType())), clock.instant());
    reportRepository.findByTenantIdAndImportJobId(job.getTenantId(), job.getId()).ifPresent(existing -> {
      reportRepository.delete(existing);
      reportRepository.flush();
    });
    ValidationReport saved = reportRepository.save(report);
    auditEventService.record("import_job.validated", "import_job", job.getId().toString(), null, saved.getSummary());
    return saved;
  }

  @Transactional(readOnly = true)
  public ValidationReport validationReport(UUID jobId) {
    ImportJob job = get(jobId);
    return reportRepository.findByTenantIdAndImportJobId(job.getTenantId(), job.getId()).orElseThrow(() -> new IllegalArgumentException("Validation report not found"));
  }

  @Transactional
  public ImportJob apply(UUID jobId) {
    ImportJob job = get(jobId);
    if (!"VALIDATED".equals(job.getStatus())) {
      throw new IllegalArgumentException("Only fully validated imports can be applied in Stage 2");
    }
    job.markApplied(clock.instant());
    auditEventService.record("import_job.applied", "import_job", job.getId().toString(), null, "{\"stage\":\"2\",\"note\":\"apply marks validated import as applied; domain upsert remains Stage 2.1\"}");
    return job;
  }

  @Transactional
  public ImportJob reject(UUID jobId) {
    ImportJob job = get(jobId);
    job.markRejected("Rejected by operator", clock.instant());
    auditEventService.record("import_job.rejected", "import_job", job.getId().toString(), null, "{\"source\":\"core-api\"}");
    return job;
  }
}
