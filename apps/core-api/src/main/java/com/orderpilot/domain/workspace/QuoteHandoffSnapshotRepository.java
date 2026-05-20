package com.orderpilot.domain.workspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteHandoffSnapshotRepository extends JpaRepository<QuoteHandoffSnapshot, UUID> {
  Optional<QuoteHandoffSnapshot> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<QuoteHandoffSnapshot> findByTenantIdAndDraftQuoteIdAndPayloadHash(UUID tenantId, UUID draftQuoteId, String payloadHash);
  Optional<QuoteHandoffSnapshot> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  Optional<QuoteHandoffSnapshot> findTopByTenantIdAndDraftQuoteIdOrderByPayloadVersionDesc(UUID tenantId, UUID draftQuoteId);
}
