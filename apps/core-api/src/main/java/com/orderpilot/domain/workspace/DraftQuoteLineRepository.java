package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
public interface DraftQuoteLineRepository extends JpaRepository<DraftQuoteLine, UUID> {
  List<DraftQuoteLine> findByTenantIdAndDraftQuoteId(UUID tenantId, UUID draftQuoteId);
  Optional<DraftQuoteLine> findByIdAndTenantId(UUID id, UUID tenantId);
  // One grouped query for queue line counts — avoids N+1 line-array loading.
  @Query("select l.draftQuoteId, count(l) from DraftQuoteLine l where l.tenantId = :tenantId and l.draftQuoteId in :ids group by l.draftQuoteId")
  List<Object[]> countByDraftQuoteIds(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);
}
