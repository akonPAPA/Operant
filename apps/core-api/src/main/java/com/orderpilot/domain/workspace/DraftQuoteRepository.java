package com.orderpilot.domain.workspace;
import jakarta.persistence.LockModeType; import java.time.Instant; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock;
public interface DraftQuoteRepository extends JpaRepository<DraftQuote, UUID> {
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftQuote> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<DraftQuote> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
  Optional<DraftQuote> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String status, String sourceType);
  List<DraftQuote> findByTenantIdAndRequiresHumanReviewOrderByCreatedAtDesc(UUID tenantId, boolean requiresHumanReview);
  List<DraftQuote> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(UUID tenantId, Instant from, Instant to);
}
