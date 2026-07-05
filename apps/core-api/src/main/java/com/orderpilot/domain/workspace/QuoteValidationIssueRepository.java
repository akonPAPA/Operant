package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuoteValidationIssueRepository extends JpaRepository<QuoteValidationIssue, UUID> {
  java.util.Optional<QuoteValidationIssue> findByIdAndTenantId(UUID id, UUID tenantId);
  List<QuoteValidationIssue> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId);
  // PR#236: bulk list-read loader — load validation issues for a bounded set of quote ids in one
  // tenant-scoped query (ordered for deterministic in-memory grouping) for the operator draft list.
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdInOrderByDraftQuoteIdAscCreatedAtAsc(UUID tenantId, java.util.Collection<UUID> draftQuoteIds);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdInAndBlockingTrueAndStatusOrderByCreatedAtAsc(
      UUID tenantId, java.util.Collection<UUID> draftQuoteIds, String status, Pageable pageable);
  @Query("""
      select i.issueCode as code, count(i) as total
      from QuoteValidationIssue i, DraftQuote q
      where i.tenantId = :tenantId
        and q.tenantId = :tenantId
        and i.draftQuoteId = q.id
        and q.sourceType = :sourceType
        and i.blocking = true
        and i.status = 'OPEN'
      group by i.issueCode
      order by count(i) desc, i.issueCode asc
      """)
  List<BlockingIssueAggregate> summarizeOpenBlockingIssuesForSourceType(
      @Param("tenantId") UUID tenantId,
      @Param("sourceType") String sourceType,
      Pageable pageable);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, String status);
  List<QuoteValidationIssue> findByTenantIdAndDraftQuoteIdAndDraftQuoteLineIdAndStatusOrderByCreatedAtAsc(UUID tenantId, UUID draftQuoteId, UUID draftQuoteLineId, String status);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrue(UUID tenantId, UUID draftQuoteId);
  long countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(UUID tenantId, UUID draftQuoteId, String status);

  interface BlockingIssueAggregate {
    String getCode();
    long getTotal();
  }
}
