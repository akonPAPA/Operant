package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import com.orderpilot.domain.intake.ObjectStorageRecordRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectStorageService {
  private final ObjectStorageRecordRepository repository; private final IntakeValidationService validationService; private final Clock clock; private final Path storageRoot;
  public ObjectStorageService(ObjectStorageRecordRepository repository, IntakeValidationService validationService, Clock clock, @Value("${orderpilot.storage.local-root:target/local-object-storage}") String localRoot){this.repository=repository; this.validationService=validationService; this.clock=clock; this.storageRoot=Path.of(localRoot);}
  @Transactional
  public ObjectStorageRecord store(String originalFilename, String contentType, byte[] bytes) {
    validationService.validateFile(originalFilename, contentType, bytes == null ? 0 : bytes.length);
    return storeValidated(originalFilename, contentType, bytes);
  }

  @Transactional
  public ObjectStorageRecord storeRawPayload(String source, String externalId, String payload) {
    byte[] bytes = payload == null ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8) : payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length == 0) throw new IllegalArgumentException("Raw payload must not be empty");
    if (bytes.length > IntakeValidationService.DEFAULT_MAX_FILE_BYTES) throw new IllegalArgumentException("Raw payload exceeds max size");
    String filename = sanitize((source == null ? "payload" : source.toLowerCase()) + "-" + (externalId == null || externalId.isBlank() ? UUID.randomUUID() : externalId) + ".json");
    return storeValidated(filename, "application/json", bytes);
  }

  private ObjectStorageRecord storeValidated(String originalFilename, String contentType, byte[] bytes) {
    UUID tenantId = TenantContext.requireTenantId(); String sha = sha256(bytes);
    String objectKey = tenantId + "/" + sha + "/" + sanitize(originalFilename == null ? "upload.bin" : originalFilename);
    try { Path target = storageRoot.resolve(objectKey).normalize(); Files.createDirectories(target.getParent()); if (!Files.exists(target)) Files.write(target, bytes); }
    catch (Exception ex) { throw new IllegalArgumentException("Unable to store uploaded object"); }
    return repository.save(new ObjectStorageRecord(tenantId, objectKey, originalFilename, contentType, bytes.length, sha, clock.instant()));
  }
  public String sha256(byte[] bytes){ try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception ex) { throw new IllegalArgumentException("Unable to calculate fingerprint"); } }
  private String sanitize(String value){ return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
