package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftOrderLineRepository extends JpaRepository<DraftOrderLine, UUID> {
  List<DraftOrderLine> findByTenantIdAndDraftOrderId(UUID tenantId, UUID draftOrderId);
  Optional<DraftOrderLine> findByIdAndTenantId(UUID id, UUID tenantId);
  // One grouped query for queue line counts — avoids N+1 line-array loading.
  @Query("select l.draftOrderId, count(l) from DraftOrderLine l where l.tenantId = :tenantId and l.draftOrderId in :ids group by l.draftOrderId")
  List<Object[]> countByDraftOrderIds(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);
}
