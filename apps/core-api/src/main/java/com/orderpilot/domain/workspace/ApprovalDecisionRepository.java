package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {
  List<ApprovalDecision> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(UUID tenantId, String targetType, UUID targetId); List<ApprovalDecision> findTop25ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
