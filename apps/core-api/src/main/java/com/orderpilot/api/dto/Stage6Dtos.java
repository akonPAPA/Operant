package com.orderpilot.api.dto;

import java.util.UUID;

public final class Stage6Dtos {
  private Stage6Dtos() {}
  public record AssignRequest(UUID userId) {}
  public record StatusRequest(String status) {}
  public record ApprovalDecisionRequest(String targetType, UUID targetId, String decision, String reason, UUID decidedBy) {}
  public record NoteRequest(String targetType, UUID targetId, String noteText, UUID createdBy) {}
}
