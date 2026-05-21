package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.api.dto.Stage2Dtos.ImportJobResponse;
import com.orderpilot.api.dto.Stage2Dtos.ImportRowRequest;
import com.orderpilot.api.dto.Stage2Dtos.ImportRowResponse;
import com.orderpilot.api.dto.Stage2Dtos.ValidationReportResponse;
import com.orderpilot.application.services.ImportJobService;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.domain.imports.ImportStagingRow;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/imports", "/api/v1/imports/jobs", "/api/v1/import-jobs"})
public class ImportController {
  private final ImportJobService service;
  public ImportController(ImportJobService service) { this.service = service; }

  @PostMapping public ImportJobResponse create(@RequestBody ImportJobRequest request) { return toResponse(service.create(request)); }
  @GetMapping public List<ImportJobResponse> list() { return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/{id}") public ImportJobResponse get(@PathVariable UUID id) { return toResponse(service.get(id)); }
  @PostMapping("/{id}/rows") public ImportRowResponse addRow(@PathVariable UUID id, @RequestBody ImportRowRequest request) { return toResponse(service.addRow(id, request)); }
  @PostMapping("/{id}/validate") public ValidationReportResponse validate(@PathVariable UUID id) { return service.validate(id); }
  @GetMapping("/{id}/validation-report") public ValidationReportResponse validationReport(@PathVariable UUID id) { return service.validationReport(id); }
  @PostMapping("/{id}/apply") public ImportJobResponse apply(@PathVariable UUID id) { return toResponse(service.activate(id)); }
  @PostMapping("/{id}/activate") public ImportJobResponse activate(@PathVariable UUID id) { return toResponse(service.activate(id)); }
  @PostMapping("/{id}/reject") public ImportJobResponse reject(@PathVariable UUID id) { return toResponse(service.reject(id)); }

  private ImportJobResponse toResponse(ImportJob job) { return new ImportJobResponse(job.getId(), job.getImportType(), job.getOriginalFilename(), job.getStatus(), job.getTotalRows(), job.getValidRows(), job.getInvalidRows(), job.getErrorSummary()); }
  private ImportRowResponse toResponse(ImportStagingRow row) { return new ImportRowResponse(row.getId(), row.getRowNumber(), row.getValidationStatus(), row.getMappedData(), row.getValidationErrors()); }
}
