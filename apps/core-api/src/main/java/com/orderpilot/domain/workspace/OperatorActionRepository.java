package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface OperatorActionRepository extends JpaRepository<OperatorAction, UUID> {
  List<OperatorAction> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(UUID tenantId, String targetType, UUID targetId); List<OperatorAction> findTop25ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
