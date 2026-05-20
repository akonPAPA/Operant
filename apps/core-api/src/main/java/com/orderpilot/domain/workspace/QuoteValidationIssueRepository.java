package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteValidationIssueRepository extends JpaRepository<QuoteValidationIssue, UUID> {
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, String status);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndDraftQuoteLineIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, UUID draftQuoteLineId, String status);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrue(UUID tenantId, UUID draftQuoteId);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(UUID tenantId, UUID draftQuoteId, String status);
}
