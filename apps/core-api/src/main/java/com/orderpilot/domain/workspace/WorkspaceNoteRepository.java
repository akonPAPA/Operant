package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkspaceNoteRepository extends JpaRepository<WorkspaceNote, UUID> {
  List<WorkspaceNote> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(UUID tenantId, String targetType, UUID targetId); Optional<WorkspaceNote> findByIdAndTenantId(UUID id, UUID tenantId);
}
