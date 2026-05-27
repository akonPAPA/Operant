package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftQuoteRepository extends JpaRepository<DraftQuote, UUID> {
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftQuote> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status);
  Optional<DraftQuote> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  List<DraftQuote> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  List<DraftQuote> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType);
  List<DraftQuote> findByTenantIdAndStatusAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String status, String sourceType);
}
