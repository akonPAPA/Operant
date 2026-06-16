package com.orderpilot.domain.trust;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTrustRunRepository extends JpaRepository<DocumentTrustRun, UUID> {
  Optional<DocumentTrustRun> findByIdAndTenantId(UUID id, UUID tenantId);

  List<DocumentTrustRun> findByTenantIdAndSourceDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID sourceDocumentId);

  // Idempotency: an explicit caller token collapses repeat evaluations onto one active run.
  Optional<DocumentTrustRun> findFirstByTenantIdAndIdempotencyKeyAndActiveTrue(UUID tenantId, String idempotencyKey);

  // Idempotency: natural key (same source document + identical content) when no explicit token given.
  Optional<DocumentTrustRun> findFirstByTenantIdAndSourceDocumentIdAndContentSha256AndIdempotencyKeyIsNullAndActiveTrue(
      UUID tenantId, UUID sourceDocumentId, String contentSha256);
}
