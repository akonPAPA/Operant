package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftOrderRepository extends JpaRepository<DraftOrder, UUID> {
  List<DraftOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftOrder> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status); long countByTenantId(UUID tenantId);
  @Query("select o from DraftOrder o where o.tenantId = :tenantId and (:status is null or o.status = :status) and (:caseId is null or o.sourceExceptionCaseId = :caseId) order by o.createdAt desc")
  List<DraftOrder> searchReviewQueue(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("caseId") UUID caseId, Pageable pageable);
  Optional<DraftOrder> findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID sourceExceptionCaseId);
}
