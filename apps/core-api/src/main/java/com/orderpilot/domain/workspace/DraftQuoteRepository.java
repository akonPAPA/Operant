package com.orderpilot.domain.workspace;
import jakarta.persistence.LockModeType; import java.time.Instant; import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftQuoteRepository extends JpaRepository<DraftQuote, UUID> {
  @Query("select q from DraftQuote q where q.tenantId = :tenantId and (:status is null or q.status = :status) and (:caseId is null or q.sourceExceptionCaseId = :caseId) and (:customerRef is null or lower(q.customerDisplayName) like lower(concat('%', :customerRef, '%'))) order by q.createdAt desc")
  List<DraftQuote> searchReviewQueue(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("caseId") UUID caseId, @Param("customerRef") String customerRef, Pageable pageable);
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftQuote> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status); long countByTenantId(UUID tenantId);
  // OP-CAP-15J: bounded recent-window draft fetch (any source) for the recent remediation rollup tile.
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<DraftQuote> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<DraftQuote> findWithLockByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  Optional<DraftQuote> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  Optional<DraftQuote> findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceExceptionCaseId);
  Optional<DraftQuote> findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceValidationRunId);
  // OP-CAP-15C: review-origin draft queue (validation-review-backed quotes only), tenant-scoped, paginated.
  @Query("select q from DraftQuote q where q.tenantId = :tenantId and q.sourceValidationRunId is not null and (:status is null or q.status = :status) order by q.createdAt desc")
  List<DraftQuote> findReviewOriginDrafts(@Param("tenantId") UUID tenantId, @Param("status") String status, Pageable pageable);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String status, String sourceType);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status, Pageable pageable);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType, Pageable pageable);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String status, String sourceType, Pageable pageable);
  long countByTenantIdAndSourceType(UUID tenantId, String sourceType);
  long countByTenantIdAndSourceTypeAndRequiresHumanReviewTrue(UUID tenantId, String sourceType);
  long countByTenantIdAndSourceTypeAndStatus(UUID tenantId, String sourceType, String status);
  long countByTenantIdAndSourceTypeAndStatusIn(UUID tenantId, String sourceType, Collection<String> statuses);
  List<DraftQuote> findByTenantIdAndIdempotencyKeyIn(UUID tenantId, Collection<String> idempotencyKeys);
  // PR#236: tie-stable bounded ordering (createdAt desc, id desc) for the operator draft-quote list.
  // The secondary id key gives a deterministic total order so quotes sharing the same createdAt do
  // not overlap or reorder across pages. Filter shape matches the existing single-key variants.
  List<DraftQuote> findByTenantIdOrderByCreatedAtDescIdDesc(UUID tenantId, Pageable pageable);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDescIdDesc(UUID tenantId, String status, Pageable pageable);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDescIdDesc(UUID tenantId, String sourceType, Pageable pageable);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDescIdDesc(UUID tenantId, String status, String sourceType, Pageable pageable);
  List<DraftQuote> findByTenantIdAndRequiresHumanReviewOrderByCreatedAtDesc(UUID tenantId, boolean requiresHumanReview);
  List<DraftQuote> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(UUID tenantId, Instant from, Instant to);
}
