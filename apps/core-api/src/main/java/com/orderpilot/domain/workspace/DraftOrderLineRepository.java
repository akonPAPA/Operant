package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftOrderLineRepository extends JpaRepository<DraftOrderLine, UUID> {
  List<DraftOrderLine> findByTenantIdAndDraftOrderId(UUID tenantId, UUID draftOrderId);
  Optional<DraftOrderLine> findByIdAndTenantId(UUID id, UUID tenantId);
}
