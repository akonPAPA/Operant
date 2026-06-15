package com.orderpilot.common.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
  Optional<IdempotencyRecord> findByTenantIdAndKeyHash(UUID tenantId, String keyHash);
}
