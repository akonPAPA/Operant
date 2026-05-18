package com.orderpilot.domain.workspace;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;
@Entity @Table(name="operator_action")
public class OperatorAction {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="actor_user_id") private UUID actorUserId; @Column(name="target_type",nullable=false) private String targetType; @Column(name="target_id",nullable=false) private UUID targetId; @Column(name="action_type",nullable=false) private String actionType; private String message; @JdbcTypeCode(SqlTypes.JSON) @Column(name="metadata_json",columnDefinition="jsonb") private String metadataJson; @Column(name="created_at",nullable=false) private Instant createdAt;
  protected OperatorAction() {}
  public OperatorAction(UUID tenantId, UUID actorUserId, String targetType, UUID targetId, String actionType, String message, String metadataJson, Instant now){this.tenantId=tenantId;this.actorUserId=actorUserId;this.targetType=targetType;this.targetId=targetId;this.actionType=actionType;this.message=message;this.metadataJson=metadataJson;this.createdAt=now;}
  public UUID getId(){return id;} public String getTargetType(){return targetType;} public UUID getTargetId(){return targetId;} public String getActionType(){return actionType;} public String getMessage(){return message;} public Instant getCreatedAt(){return createdAt;}
}
