package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTrustSignalRepository extends JpaRepository<DocumentTrustSignal, UUID> {
  List<DocumentTrustSignal> findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(UUID tenantId, UUID trustRunId);
}
