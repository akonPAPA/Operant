package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteValidationIssueRepository extends JpaRepository<QuoteValidationIssue, UUID> {
  java.util.Optional<QuoteValidationIssue> findByIdAndTenantId(UUID id, UUID tenantId);
  List<QuoteValidationIssue> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId);
  // PR#236: bulk list-read loader — load validation issues for a bounded set of quote ids in one
  // tenant-scoped query (ordered for deterministic in-memory grouping) for the operator draft list.
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdInOrderByDraftQuoteIdAscCreatedAtAsc(UUID tenantId, java.util.Collection<UUID> draftQuoteIds);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, String status);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndDraftQuoteLineIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, UUID draftQuoteLineId, String status);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrue(UUID tenantId, UUID draftQuoteId);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(UUID tenantId, UUID draftQuoteId, String status);
}
