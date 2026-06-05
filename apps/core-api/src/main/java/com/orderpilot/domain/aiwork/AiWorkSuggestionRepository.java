package com.orderpilot.domain.aiwork;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AiWorkSuggestionRepository extends JpaRepository<AiWorkSuggestion, UUID> {
  Optional<AiWorkSuggestion> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<AiWorkSuggestion> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

  List<AiWorkSuggestion> findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
      UUID tenantId, String sourceType, UUID sourceId);

  List<AiWorkSuggestion> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  /** Pessimistic write lock for operator accept/reject commands — prevents concurrent decisions. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AiWorkSuggestion> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
}
