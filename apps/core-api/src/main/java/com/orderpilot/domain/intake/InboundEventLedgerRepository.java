package com.orderpilot.domain.intake;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEventLedgerRepository extends JpaRepository<InboundEventLedger, UUID> {
  List<InboundEventLedger> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  List<InboundEventLedger> findByTenantIdOrderByReceivedAtDesc(UUID tenantId, Pageable pageable);
  Optional<InboundEventLedger> findByIdAndTenantId(UUID id, UUID tenantId);
  boolean existsByTenantIdAndSourceAndExternalEventId(UUID tenantId, String source, String externalEventId);
  Optional<InboundEventLedger> findFirstByTenantIdAndSourceAndFingerprintSha256OrderByReceivedAtDesc(UUID tenantId, String source, String fingerprintSha256);
}
