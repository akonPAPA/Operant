package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftOrderRepository extends JpaRepository<DraftOrder, UUID> {
  List<DraftOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftOrder> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status); long countByTenantId(UUID tenantId);
  // OP-CAP-15J: bounded recent-window draft fetch (any source) for the recent remediation rollup tile.
  List<DraftOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
  @Query("select o from DraftOrder o where o.tenantId = :tenantId and (:status is null or o.status = :status) and (:caseId is null or o.sourceExceptionCaseId = :caseId) order by o.createdAt desc")
  List<DraftOrder> searchReviewQueue(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("caseId") UUID caseId, Pageable pageable);
  Optional<DraftOrder> findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceExceptionCaseId);
  Optional<DraftOrder> findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceValidationRunId);
  // OP-CAP-15C: review-origin draft queue (validation-review-backed orders only), tenant-scoped, paginated.
  @Query("select o from DraftOrder o where o.tenantId = :tenantId and o.sourceValidationRunId is not null and (:status is null or o.status = :status) order by o.createdAt desc")
  List<DraftOrder> findReviewOriginDrafts(@Param("tenantId") UUID tenantId, @Param("status") String status, Pageable pageable);
}
