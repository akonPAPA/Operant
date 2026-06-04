package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteSourceLinkRepository extends JpaRepository<QuoteSourceLink, UUID> {
  Optional<QuoteSourceLink> findFirstByTenantIdAndQuoteId(UUID tenantId, UUID quoteId);
  List<QuoteSourceLink> findByTenantIdAndSourceTypeAndSourceIdOrderBySourceReceivedAtDesc(UUID tenantId, String sourceType, UUID sourceId);
}
