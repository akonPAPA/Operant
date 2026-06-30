package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage3Dtos.ApiDocumentUploadRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.InboundEventLedger;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
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
  private final InboundDocumentRepository repository; private final InboundEventLedgerRepository ledgerRepository; private final ObjectStorageService storageService; private final ProcessingJobService jobService; private final AuditEventService auditEventService; private final Clock clock;
  public InboundDocumentService(InboundDocumentRepository repository, InboundEventLedgerRepository ledgerRepository, ObjectStorageService storageService, ProcessingJobService jobService, AuditEventService auditEventService, Clock clock){this.repository=repository; this.ledgerRepository=ledgerRepository; this.storageService=storageService; this.jobService=jobService; this.auditEventService=auditEventService; this.clock=clock;}
  @Transactional(readOnly=true) public List<InboundDocument> list(){ return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly=true) public InboundDocument get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Inbound document not found")); }
  @Transactional public InboundDocument createFromMultipart(MultipartFile file, String sourceChannel, String documentType, String receivedFrom, String subject) {
    // Request thread: validate, persist object metadata, enqueue async processing only after secure store.
    try {
      return createStored(sourceChannel, documentType, file.getOriginalFilename(), file.getContentType(), file.getBytes(), receivedFrom, subject, "{}");
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Unable to read uploaded file");
    }
  }
  @Transactional public InboundDocument createFromApi(ApiDocumentUploadRequest request) {
    if (request.contentBase64() == null || request.contentBase64().isBlank()) throw new IllegalArgumentException("contentBase64 is required");
    byte[] bytes;
    try { bytes = Base64.getDecoder().decode(request.contentBase64()); }
    catch (IllegalArgumentException ex) { throw new IllegalArgumentException("contentBase64 must be valid Base64"); }
    return createStored(request.sourceChannel() == null ? "API_UPLOAD" : request.sourceChannel(), request.documentType(), request.originalFilename(), request.contentType(), bytes, request.receivedFrom(), request.subject(), request.rawMetadata());
  }
  private InboundDocument createStored(String sourceChannel, String documentType, String originalFilename, String contentType, byte[] bytes, String receivedFrom, String subject, String rawMetadata) {
    UUID tenantId = TenantContext.requireTenantId(); ObjectStorageRecord stored = storageService.store(originalFilename, contentType, bytes);
    InboundDocument doc = new InboundDocument(tenantId, sourceChannel == null ? "API_UPLOAD" : sourceChannel, documentType == null ? "UNKNOWN" : documentType, "STORED", originalFilename, stored.getContentType(), stored.getFileSizeBytes(), stored.getObjectKey(), stored.getSha256Fingerprint(), receivedFrom, subject, rawMetadata, clock.instant());
    if (repository.findFirstByTenantIdAndSha256FingerprintOrderByReceivedAtDesc(tenantId, stored.getSha256Fingerprint()).isPresent()) {
      doc.markDuplicate(clock.instant()); InboundDocument saved = repository.save(doc); ledgerRepository.save(new InboundEventLedger(tenantId, doc.getSourceChannel(), stored.getSha256Fingerprint(), "DOCUMENT_RECEIVED", stored.getSha256Fingerprint(), "DUPLICATE", stored.getObjectKey(), clock.instant())); auditEventService.record("inbound_document.duplicate", "inbound_document", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
    }
    doc.markQueued(clock.instant()); InboundDocument saved = repository.save(doc); ledgerRepository.save(new InboundEventLedger(tenantId, saved.getSourceChannel(), stored.getSha256Fingerprint(), "DOCUMENT_RECEIVED", stored.getSha256Fingerprint(), "QUEUED", stored.getObjectKey(), clock.instant())); jobService.enqueue(tenantId, "DOCUMENT_RECEIVED", "INBOUND_DOCUMENT", saved.getId()); auditEventService.record("inbound_document.received", "inbound_document", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
  }
}
