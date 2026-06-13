package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface OperatorActionRepository extends JpaRepository<OperatorAction, UUID> {
  List<OperatorAction> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(UUID tenantId, String targetType, UUID targetId); List<OperatorAction> findTop25ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  // OP-CAP-15G: batch-load structured actions of one kind for many targets (review-origin draft queue lineage).
  List<OperatorAction> findByTenantIdAndTargetTypeAndActionTypeAndTargetIdIn(UUID tenantId, String targetType, String actionType, Collection<UUID> targetIds);
}
