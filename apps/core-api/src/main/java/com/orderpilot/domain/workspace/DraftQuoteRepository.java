package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftQuoteRepository extends JpaRepository<DraftQuote, UUID> {
  List<DraftQuote> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftQuote> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status);
}
