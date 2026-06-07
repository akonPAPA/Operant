package com.orderpilot.domain.validation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiValidationHandoffReviewRepository extends JpaRepository<AiValidationHandoffReview, UUID> {
  Optional<AiValidationHandoffReview> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<AiValidationHandoffReview> findByTenantIdAndHandoffId(UUID tenantId, UUID handoffId);

  // OP-CAP-08B review queue: bulk-load review rows for a page of handoffs (avoids N+1).
  List<AiValidationHandoffReview> findByTenantIdAndHandoffIdIn(UUID tenantId, Collection<UUID> handoffIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select review from AiValidationHandoffReview review
      where review.tenantId = :tenantId and review.handoffId = :handoffId
      """)
  Optional<AiValidationHandoffReview> findLockedByTenantIdAndHandoffId(
      @Param("tenantId") UUID tenantId,
      @Param("handoffId") UUID handoffId);
}
