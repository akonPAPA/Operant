package com.orderpilot.domain.workspace;
import jakarta.persistence.LockModeType; import java.time.Instant; import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftQuoteRepository extends JpaRepository<DraftQuote, UUID> {
  @Query("select q from DraftQuote q where q.tenantId = :tenantId and (:status is null or q.status = :status) and (:caseId is null or q.sourceExceptionCaseId = :caseId) and (:customerRef is null or lower(q.customerDisplayName) like lower(concat('%', :customerRef, '%'))) order by q.createdAt desc")
  List<DraftQuote> searchReviewQueue(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("caseId") UUID caseId, @Param("customerRef") String customerRef, Pageable pageable);
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftQuote> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<DraftQuote> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
  Optional<DraftQuote> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  Optional<DraftQuote> findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceExceptionCaseId);
  Optional<DraftQuote> findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceValidationRunId);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String status, String sourceType);
  List<DraftQuote> findByTenantIdAndRequiresHumanReviewOrderByCreatedAtDesc(UUID tenantId, boolean requiresHumanReview);
  List<DraftQuote> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(UUID tenantId, Instant from, Instant to);
}
