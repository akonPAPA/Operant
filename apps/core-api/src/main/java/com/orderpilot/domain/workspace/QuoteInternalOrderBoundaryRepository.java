package com.orderpilot.domain.workspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteInternalOrderBoundaryRepository extends JpaRepository<QuoteInternalOrderBoundary, UUID> {
  Optional<QuoteInternalOrderBoundary> findByTenantIdAndDraftQuoteId(UUID tenantId, UUID draftQuoteId);
  Optional<QuoteInternalOrderBoundary> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
