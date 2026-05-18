package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage3Dtos.ApiDocumentUploadRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InboundDocumentService {
  private final InboundDocumentRepository repository; private final ObjectStorageService storageService; private final ProcessingJobService jobService; private final AuditEventService auditEventService; private final Clock clock;
  public InboundDocumentService(InboundDocumentRepository repository, ObjectStorageService storageService, ProcessingJobService jobService, AuditEventService auditEventService, Clock clock){this.repository=repository; this.storageService=storageService; this.jobService=jobService; this.auditEventService=auditEventService; this.clock=clock;}
  @Transactional(readOnly=true) public List<InboundDocument> list(){ return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly=true) public InboundDocument get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Inbound document not found")); }
  @Transactional public InboundDocument createFromMultipart(MultipartFile file, String sourceChannel, String documentType, String receivedFrom, String subject) {
    try { return createStored(sourceChannel, documentType, file.getOriginalFilename(), file.getContentType(), file.getBytes(), receivedFrom, subject, "{}"); }
    catch (Exception ex) { throw new IllegalArgumentException("Unable to read uploaded file"); }
  }
  @Transactional public InboundDocument createFromApi(ApiDocumentUploadRequest request) {
    byte[] bytes = Base64.getDecoder().decode(request.contentBase64());
    return createStored(request.sourceChannel() == null ? "API_UPLOAD" : request.sourceChannel(), request.documentType(), request.originalFilename(), request.contentType(), bytes, request.receivedFrom(), request.subject(), request.rawMetadata());
  }
  private InboundDocument createStored(String sourceChannel, String documentType, String originalFilename, String contentType, byte[] bytes, String receivedFrom, String subject, String rawMetadata) {
    UUID tenantId = TenantContext.requireTenantId(); ObjectStorageRecord stored = storageService.store(originalFilename, contentType, bytes);
    InboundDocument doc = new InboundDocument(tenantId, sourceChannel == null ? "API_UPLOAD" : sourceChannel, documentType == null ? "UNKNOWN" : documentType, "STORED", originalFilename, stored.getContentType(), stored.getFileSizeBytes(), stored.getObjectKey(), stored.getSha256Fingerprint(), receivedFrom, subject, rawMetadata, clock.instant());
    if (repository.findFirstByTenantIdAndSha256FingerprintOrderByReceivedAtDesc(tenantId, stored.getSha256Fingerprint()).isPresent()) {
      doc.markDuplicate(clock.instant()); InboundDocument saved = repository.save(doc); auditEventService.record("inbound_document.duplicate", "inbound_document", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
    }
    doc.markQueued(clock.instant()); InboundDocument saved = repository.save(doc); jobService.enqueue(tenantId, "DOCUMENT_PROCESSING", "INBOUND_DOCUMENT", saved.getId()); auditEventService.record("inbound_document.received", "inbound_document", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
  }
}