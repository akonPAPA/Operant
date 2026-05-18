package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftQuoteLineRepository extends JpaRepository<DraftQuoteLine, UUID> {
  List<DraftQuoteLine> findByTenantIdAndDraftQuoteId(UUID tenantId, UUID draftQuoteId);
}
