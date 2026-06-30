package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.application.services.intake.FileThreatScanService;
import com.orderpilot.application.services.intake.PassThroughFileThreatScanService;
import com.orderpilot.application.services.intake.UploadedFileContentInspector;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class InboundDocumentServiceSecurityTest {

  private final InboundDocumentRepository repository = mock(InboundDocumentRepository.class);
  private final InboundEventLedgerRepository ledgerRepository = mock(InboundEventLedgerRepository.class);
  private final ProcessingJobService jobService = mock(ProcessingJobService.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void rejectedUploadDoesNotEnqueueProcessingJob() {
    TenantContext.setTenantId(UUID.randomUUID());
    ObjectStorageService storage = new ObjectStorageService(
        mock(com.orderpilot.domain.intake.ObjectStorageRecordRepository.class),
        new IntakeValidationService(),
        new UploadedFileContentInspector(false),
        new PassThroughFileThreatScanService(),
        clock,
        "target/test-upload-security");
    InboundDocumentService service = new InboundDocumentService(
        repository, ledgerRepository, storage, jobService, auditEventService, clock);

    MockMultipartFile file = new MockMultipartFile(
        "file", "evil.pdf", "application/pdf", "not-pdf".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.createFromMultipart(file, "MANUAL_UPLOAD", "RFQ", null, null))
        .isInstanceOf(IllegalArgumentException.class);

    verify(jobService, never()).enqueue(any(), anyString(), anyString(), any());
  }

  @Test
  void quarantinedScanVerdictDoesNotEnqueueProcessingJob() {
    TenantContext.setTenantId(UUID.randomUUID());
    FileThreatScanService quarantine = (bytes, type) -> FileThreatScanService.Verdict.QUARANTINED;
    var storageRepo = mock(com.orderpilot.domain.intake.ObjectStorageRecordRepository.class);
    when(storageRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    ObjectStorageService storage = new ObjectStorageService(
        storageRepo,
        new IntakeValidationService(),
        new UploadedFileContentInspector(false),
        quarantine,
        clock,
        "target/test-upload-quarantine");
    InboundDocumentService service = new InboundDocumentService(
        repository, ledgerRepository, storage, jobService, auditEventService, clock);

    byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
    MockMultipartFile file = new MockMultipartFile("file", "ok.pdf", "application/pdf", pdf);

    assertThatThrownBy(() -> service.createFromMultipart(file, "MANUAL_UPLOAD", "RFQ", null, null))
        .hasMessageContaining("security policy");

    verify(jobService, never()).enqueue(any(), anyString(), anyString(), any());
  }
}
