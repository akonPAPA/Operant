package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.DocumentFingerprint;
import com.orderpilot.domain.trust.DocumentFingerprintRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Computes and stores a tenant-scoped document fingerprint from a caller-provided canonical
 * metadata/hash input. Only the resulting SHA-256 hash is persisted — never raw document content.
 * Duplicate detection is strictly tenant-scoped: a hash collision across tenants is never reported.
 */
@Service
public class DocumentFingerprintService {
  private final DocumentFingerprintRepository fingerprints;
  private final Clock clock;

  public DocumentFingerprintService(DocumentFingerprintRepository fingerprints, Clock clock) {
    this.fingerprints = fingerprints;
    this.clock = clock;
  }

  /**
   * Hashes the canonical input, detects same-tenant duplicates (excluding the same source document),
   * and stores a fingerprint row.
   */
  @Transactional
  public FingerprintResult fingerprint(UUID tenantId, UUID sourceDocumentId, String canonicalHashInput, Long contentByteSize) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (sourceDocumentId == null) {
      throw new IllegalArgumentException("sourceDocumentId is required");
    }
    if (canonicalHashInput == null || canonicalHashInput.isBlank()) {
      throw new IllegalArgumentException("canonicalHashInput is required");
    }

    String sha256 = sha256Hex(canonicalHashInput);

    // Tenant-scoped duplicate detection. A different tenant with identical content never matches.
    List<DocumentFingerprint> sameHash = fingerprints.findByTenantIdAndContentSha256(tenantId, sha256);
    boolean duplicate = sameHash.stream()
        .anyMatch(existing -> !existing.getSourceDocumentId().equals(sourceDocumentId));

    DocumentFingerprint saved = fingerprints.save(
        new DocumentFingerprint(tenantId, sourceDocumentId, sha256, contentByteSize, clock.instant()));

    return new FingerprintResult(saved.getId(), sha256, duplicate);
  }

  /** Computes the hex SHA-256 of a canonical input. Validates non-blank; never stores the input. */
  public String computeSha256(String canonicalHashInput) {
    if (canonicalHashInput == null || canonicalHashInput.isBlank()) {
      throw new IllegalArgumentException("canonicalHashInput is required");
    }
    return sha256Hex(canonicalHashInput);
  }

  private String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required but unavailable", ex);
    }
  }

  public record FingerprintResult(UUID fingerprintId, String contentSha256, boolean duplicate) {
  }
}
