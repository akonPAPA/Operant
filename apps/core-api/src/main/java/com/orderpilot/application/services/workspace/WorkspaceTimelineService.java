package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.workspace.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceTimelineService {
  private final OperatorActionService actionService; private final ApprovalWorkflowService approvalService; private final WorkspaceNoteService noteService;
  public WorkspaceTimelineService(OperatorActionService actionService, ApprovalWorkflowService approvalService, WorkspaceNoteService noteService){this.actionService=actionService;this.approvalService=approvalService;this.noteService=noteService;}
  @Transactional(readOnly = true)
  public List<TimelineItem> timeline(String targetType, UUID targetId) {
    List<TimelineItem> actions = actionService.forTarget(targetType, targetId).stream().map(a -> new TimelineItem("OPERATOR_ACTION", a.getActionType(), a.getMessage(), a.getCreatedAt())).toList();
    List<TimelineItem> decisions = approvalService.forTarget(targetType, targetId).stream().map(d -> new TimelineItem("APPROVAL_DECISION", d.getDecision(), d.getReason(), d.getDecidedAt())).toList();
    List<TimelineItem> notes = noteService.list(targetType, targetId).stream().map(n -> new TimelineItem("NOTE", "NOTE_ADDED", n.getNoteText(), n.getCreatedAt())).toList();
    return java.util.stream.Stream.of(actions, decisions, notes).flatMap(List::stream).sorted(Comparator.comparing(TimelineItem::createdAt).reversed()).toList();
  }
  public record TimelineItem(String type, String action, String message, Instant createdAt) {}
}
