package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import com.orderpilot.domain.intake.ObjectStorageRecordRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectStorageService {
  private static final int MAX_OBJECT_ID_LENGTH = 128;
  private static final Set<String> SAFE_EXTENSIONS = Set.of(".pdf", ".csv", ".xls", ".xlsx", ".png", ".jpg", ".jpeg", ".txt", ".json");

  private final ObjectStorageRecordRepository repository; private final IntakeValidationService validationService; private final Clock clock; private final Path storageRoot;
  public ObjectStorageService(ObjectStorageRecordRepository repository, IntakeValidationService validationService, Clock clock, @Value("${orderpilot.storage.local-root:target/local-object-storage}") String localRoot){this.repository=repository; this.validationService=validationService; this.clock=clock; this.storageRoot=Path.of(localRoot).toAbsolutePath().normalize();}
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
    String filename = sanitizeDisplayName((source == null ? "payload" : source.toLowerCase()) + "-" + (externalId == null || externalId.isBlank() ? UUID.randomUUID() : externalId) + ".json");
    return storeValidated(filename, "application/json", bytes);
  }

  private ObjectStorageRecord storeValidated(String originalFilename, String contentType, byte[] bytes) {
    UUID tenantId = TenantContext.requireTenantId(); String sha = sha256(bytes);
    String objectId = UUID.randomUUID().toString();
    String extension = extensionFor(contentType);
    String objectKey = objectKey(tenantId, sha, objectId, extension);
    Path target = resolveLocalObjectPath(tenantId, sha, objectId, extension);
    try { Files.createDirectories(target.getParent()); if (!Files.exists(target)) Files.write(target, bytes); }
    catch (IOException ex) { throw new IllegalArgumentException("Unable to store uploaded object", ex); }
    return repository.save(new ObjectStorageRecord(tenantId, objectKey, originalFilename, contentType, bytes.length, sha, clock.instant()));
  }
  public String sha256(byte[] bytes){ try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception ex) { throw new IllegalArgumentException("Unable to calculate fingerprint"); } }

  Path resolveLocalObjectPath(UUID tenantId, String sha256Fingerprint, String objectId, String extension) {
    String safeTenant = requireSafeSegment(tenantId == null ? "" : tenantId.toString(), "tenant id");
    String safeSha = requireSha256(sha256Fingerprint);
    String safeObjectId = requireSafeSegment(objectId, "object id");
    String safeExtension = requireSafeExtension(extension);
    Path target = storageRoot.resolve(safeTenant).resolve(safeSha).resolve(safeObjectId + safeExtension).normalize();
    if (!target.startsWith(storageRoot)) throw new IllegalArgumentException("Object storage path escapes storage root");
    return target;
  }

  private String objectKey(UUID tenantId, String sha256Fingerprint, String objectId, String extension) {
    String safeTenant = requireSafeSegment(tenantId == null ? "" : tenantId.toString(), "tenant id");
    String safeSha = requireSha256(sha256Fingerprint);
    String safeObjectId = requireSafeSegment(objectId, "object id");
    String safeExtension = requireSafeExtension(extension);
    return safeTenant + "/" + safeSha + "/" + safeObjectId + safeExtension;
  }

  private String extensionFor(String contentType) {
    return switch (contentType == null ? "" : contentType) {
      case "application/pdf" -> ".pdf";
      case "text/csv" -> ".csv";
      case "application/vnd.ms-excel" -> ".xls";
      case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      case "text/plain" -> ".txt";
      case "application/json" -> ".json";
      default -> throw new IllegalArgumentException("Unsupported content type");
    };
  }

  private String requireSha256(String value) {
    if (value == null || value.length() != 64) throw new IllegalArgumentException("Invalid sha256 fingerprint");
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
      if (!hex) throw new IllegalArgumentException("Invalid sha256 fingerprint");
    }
    return value;
  }

  private String requireSafeSegment(String value, String label) {
    if (value == null || value.isBlank() || value.length() > MAX_OBJECT_ID_LENGTH) throw new IllegalArgumentException("Invalid " + label);
    if (".".equals(value) || "..".equals(value)) throw new IllegalArgumentException("Invalid " + label);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      boolean safe = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
      if (!safe) throw new IllegalArgumentException("Invalid " + label);
    }
    return value;
  }

  private String requireSafeExtension(String extension) {
    if (extension == null || !SAFE_EXTENSIONS.contains(extension)) throw new IllegalArgumentException("Unsupported file extension");
    return extension;
  }

  private String sanitizeDisplayName(String value) {
    StringBuilder sanitized = new StringBuilder(value == null ? 0 : value.length());
    if (value != null) {
      for (int i = 0; i < value.length(); i++) {
        char ch = value.charAt(i);
        boolean safe = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
        sanitized.append(safe ? ch : '_');
      }
    }
    return sanitized.isEmpty() ? "payload.json" : sanitized.toString();
  }
}
