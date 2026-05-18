package com.orderpilot.domain.workspace;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="workspace_note")
public class WorkspaceNote {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="target_type",nullable=false) private String targetType; @Column(name="target_id",nullable=false) private UUID targetId; @Column(name="note_text",nullable=false) private String noteText; @Column(name="created_by") private UUID createdBy; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected WorkspaceNote() {}
  public WorkspaceNote(UUID tenantId, String targetType, UUID targetId, String noteText, UUID createdBy, Instant now){this.tenantId=tenantId;this.targetType=targetType;this.targetId=targetId;this.noteText=noteText;this.createdBy=createdBy;this.createdAt=now;this.updatedAt=now;}
  public void update(String noteText, Instant now){this.noteText=noteText;this.updatedAt=now;} public UUID getId(){return id;} public String getTargetType(){return targetType;} public UUID getTargetId(){return targetId;} public String getNoteText(){return noteText;} public UUID getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;}
}
