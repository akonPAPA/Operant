package com.orderpilot.security.policy;

import com.orderpilot.domain.integration.CompensationPlanStatus;
import com.orderpilot.domain.integration.ConnectorCommandExecutionState;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record TenantPolicyContext(
    UUID tenantId,
    UUID actorId,
    Set<ActorRole> actorRoles,
    UUID targetTenantId,
    ResourceType resourceType,
    UUID resourceId,
    TenantPolicyAction action,
    String riskLevel,
    BigDecimal monetaryAmount,
    BigDecimal marginImpact,
    BigDecimal discountPercent,
    ConnectorCommandExecutionState connectorCommandState,
    CompensationPlanStatus compensationPlanStatus,
    String channelType,
    boolean systemActor,
    boolean approved,
    ExecutionMode executionMode) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private UUID tenantId;
    private UUID actorId;
    private Set<ActorRole> actorRoles = Set.of();
    private UUID targetTenantId;
    private ResourceType resourceType;
    private UUID resourceId;
    private TenantPolicyAction action;
    private String riskLevel;
    private BigDecimal monetaryAmount;
    private BigDecimal marginImpact;
    private BigDecimal discountPercent;
    private ConnectorCommandExecutionState connectorCommandState;
    private CompensationPlanStatus compensationPlanStatus;
    private String channelType;
    private boolean systemActor;
    private boolean approved;
    private ExecutionMode executionMode = ExecutionMode.NONE;

    public Builder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
    public Builder actorId(UUID actorId) { this.actorId = actorId; return this; }
    public Builder actorRoles(Set<ActorRole> actorRoles) { this.actorRoles = actorRoles == null ? Set.of() : Set.copyOf(actorRoles); return this; }
    public Builder targetTenantId(UUID targetTenantId) { this.targetTenantId = targetTenantId; return this; }
    public Builder resourceType(ResourceType resourceType) { this.resourceType = resourceType; return this; }
    public Builder resourceId(UUID resourceId) { this.resourceId = resourceId; return this; }
    public Builder action(TenantPolicyAction action) { this.action = action; return this; }
    public Builder riskLevel(String riskLevel) { this.riskLevel = riskLevel; return this; }
    public Builder monetaryAmount(BigDecimal monetaryAmount) { this.monetaryAmount = monetaryAmount; return this; }
    public Builder marginImpact(BigDecimal marginImpact) { this.marginImpact = marginImpact; return this; }
    public Builder discountPercent(BigDecimal discountPercent) { this.discountPercent = discountPercent; return this; }
    public Builder connectorCommandState(ConnectorCommandExecutionState connectorCommandState) { this.connectorCommandState = connectorCommandState; return this; }
    public Builder compensationPlanStatus(CompensationPlanStatus compensationPlanStatus) { this.compensationPlanStatus = compensationPlanStatus; return this; }
    public Builder channelType(String channelType) { this.channelType = channelType; return this; }
    public Builder systemActor(boolean systemActor) { this.systemActor = systemActor; return this; }
    public Builder approved(boolean approved) { this.approved = approved; return this; }
    public Builder executionMode(ExecutionMode executionMode) { this.executionMode = executionMode == null ? ExecutionMode.NONE : executionMode; return this; }
    public TenantPolicyContext build() {
      return new TenantPolicyContext(tenantId, actorId, actorRoles, targetTenantId, resourceType, resourceId, action, riskLevel, monetaryAmount, marginImpact, discountPercent, connectorCommandState, compensationPlanStatus, channelType, systemActor, approved, executionMode);
    }
  }
}
