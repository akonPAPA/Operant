package com.orderpilot.domain.trust;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentFingerprintRepository extends JpaRepository<DocumentFingerprint, UUID> {
  // Tenant-scoped duplicate-content lookup. Never matches across tenants.
  List<DocumentFingerprint> findByTenantIdAndContentSha256(UUID tenantId, String contentSha256);

  Optional<DocumentFingerprint> findByIdAndTenantId(UUID id, UUID tenantId);
}
