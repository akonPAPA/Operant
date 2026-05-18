package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftOrderRepository extends JpaRepository<DraftOrder, UUID> {
  List<DraftOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<DraftOrder> findByIdAndTenantId(UUID id, UUID tenantId); long countByTenantIdAndStatus(UUID tenantId, String status);
}
