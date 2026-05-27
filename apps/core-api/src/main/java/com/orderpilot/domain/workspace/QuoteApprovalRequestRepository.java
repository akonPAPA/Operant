package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteApprovalRequestRepository extends JpaRepository<QuoteApprovalRequest, UUID> {
  List<QuoteApprovalRequest> findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId);
  long countByTenantIdAndDraftQuoteIdAndStatus(UUID tenantId, UUID draftQuoteId, String status);
}
