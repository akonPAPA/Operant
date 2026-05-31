package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteConversionAttemptRepository extends JpaRepository<QuoteConversionAttempt, UUID> {
  Optional<QuoteConversionAttempt> findFirstByTenantIdAndSourceTypeAndSourceIdAndIdempotencyKeyAndRequestModeOrderByCreatedAtDesc(UUID tenantId, String sourceType, UUID sourceId, String idempotencyKey, String requestMode);
  Optional<QuoteConversionAttempt> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<QuoteConversionAttempt> findFirstByTenantIdAndQuoteIdOrderByCreatedAtDesc(UUID tenantId, UUID quoteId);
  List<QuoteConversionAttempt> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<QuoteConversionAttempt> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
}
