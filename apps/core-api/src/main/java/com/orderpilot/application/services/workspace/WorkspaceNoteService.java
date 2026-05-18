package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceNoteService {
  private final WorkspaceNoteRepository repository; private final OperatorActionService actionService; private final Clock clock;
  public WorkspaceNoteService(WorkspaceNoteRepository repository, OperatorActionService actionService, Clock clock){this.repository=repository;this.actionService=actionService;this.clock=clock;}
  @Transactional public WorkspaceNote add(String targetType, UUID targetId, String noteText, UUID createdBy){WorkspaceNote note=repository.save(new WorkspaceNote(TenantContext.requireTenantId(), targetType, targetId, noteText, createdBy, clock.instant())); actionService.record(createdBy, targetType, targetId, "NOTE_ADDED", "Workspace note added", "{}"); return note;}
  @Transactional(readOnly = true) public List<WorkspaceNote> list(String targetType, UUID targetId){return repository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), targetType, targetId);}
  @Transactional public WorkspaceNote update(UUID id, String noteText){WorkspaceNote note=repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(); note.update(noteText, clock.instant()); return repository.save(note);}
}
