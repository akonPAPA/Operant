package com.orderpilot.security.policy;

import com.orderpilot.domain.integration.ConnectorCommandExecutionState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TenantPolicyService {
  private static final EnumMap<ActorRole, Set<TenantPolicyAction>> ALLOWED_ACTIONS = new EnumMap<>(ActorRole.class);

  static {
    ALLOWED_ACTIONS.put(ActorRole.OWNER_ADMIN, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_QUOTES, TenantPolicyAction.VIEW_ORDERS,
        TenantPolicyAction.VIEW_PRODUCTS, TenantPolicyAction.VIEW_INVENTORY, TenantPolicyAction.VIEW_ANALYTICS, TenantPolicyAction.VIEW_AUDIT_LOG,
        TenantPolicyAction.CREATE_DRAFT_QUOTE, TenantPolicyAction.CREATE_DRAFT_ORDER, TenantPolicyAction.APPROVE_QUOTE, TenantPolicyAction.APPROVE_ORDER,
        TenantPolicyAction.APPROVE_RISKY_SUBSTITUTE, TenantPolicyAction.APPROVE_BELOW_MARGIN_DISCOUNT, TenantPolicyAction.MANAGE_INTEGRATION_SETTINGS,
        TenantPolicyAction.CREATE_CONNECTOR_COMMAND, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND, TenantPolicyAction.VIEW_CONNECTOR_COMMANDS,
        TenantPolicyAction.CREATE_COMPENSATION_PLAN, TenantPolicyAction.APPROVE_COMPENSATION_PLAN, TenantPolicyAction.VIEW_COMPENSATION_PLAN,
        TenantPolicyAction.MANAGE_CHANNEL_SETTINGS, TenantPolicyAction.MANAGE_BOT_FLOWS, TenantPolicyAction.VIEW_CHANNEL_MESSAGES,
        TenantPolicyAction.MANAGE_USERS, TenantPolicyAction.MANAGE_ROLES, TenantPolicyAction.MANAGE_TENANT_SETTINGS));
    ALLOWED_ACTIONS.put(ActorRole.OPERATOR, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_QUOTES, TenantPolicyAction.VIEW_ORDERS,
        TenantPolicyAction.VIEW_PRODUCTS, TenantPolicyAction.VIEW_INVENTORY, TenantPolicyAction.CREATE_DRAFT_QUOTE, TenantPolicyAction.CREATE_DRAFT_ORDER,
        TenantPolicyAction.VIEW_CHANNEL_MESSAGES));
    ALLOWED_ACTIONS.put(ActorRole.SALES_QUOTE_MANAGER, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_QUOTES, TenantPolicyAction.VIEW_ORDERS,
        TenantPolicyAction.VIEW_PRODUCTS, TenantPolicyAction.CREATE_DRAFT_QUOTE, TenantPolicyAction.CREATE_DRAFT_ORDER,
        TenantPolicyAction.APPROVE_QUOTE, TenantPolicyAction.APPROVE_ORDER));
    ALLOWED_ACTIONS.put(ActorRole.INVENTORY_MANAGER, EnumSet.of(
        TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_ORDERS, TenantPolicyAction.VIEW_PRODUCTS, TenantPolicyAction.VIEW_INVENTORY));
    ALLOWED_ACTIONS.put(ActorRole.INTEGRATION_ADMIN, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.MANAGE_INTEGRATION_SETTINGS, TenantPolicyAction.CREATE_CONNECTOR_COMMAND,
        TenantPolicyAction.VIEW_CONNECTOR_COMMANDS, TenantPolicyAction.CREATE_COMPENSATION_PLAN, TenantPolicyAction.VIEW_COMPENSATION_PLAN,
        TenantPolicyAction.MANAGE_CHANNEL_SETTINGS, TenantPolicyAction.VIEW_CHANNEL_MESSAGES));
    ALLOWED_ACTIONS.put(ActorRole.AUDITOR, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_QUOTES, TenantPolicyAction.VIEW_ORDERS,
        TenantPolicyAction.VIEW_AUDIT_LOG, TenantPolicyAction.VIEW_CONNECTOR_COMMANDS, TenantPolicyAction.VIEW_COMPENSATION_PLAN,
        TenantPolicyAction.VIEW_CHANNEL_MESSAGES));
    ALLOWED_ACTIONS.put(ActorRole.BOT_MANAGER, EnumSet.of(
        TenantPolicyAction.MANAGE_CHANNEL_SETTINGS, TenantPolicyAction.MANAGE_BOT_FLOWS, TenantPolicyAction.VIEW_CHANNEL_MESSAGES,
        TenantPolicyAction.VIEW_INBOUND_MESSAGES));
    ALLOWED_ACTIONS.put(ActorRole.READ_ONLY_VIEWER, EnumSet.of(
        TenantPolicyAction.VIEW_INBOUND_MESSAGES, TenantPolicyAction.VIEW_DOCUMENTS, TenantPolicyAction.VIEW_QUOTES, TenantPolicyAction.VIEW_ORDERS,
        TenantPolicyAction.VIEW_PRODUCTS, TenantPolicyAction.VIEW_INVENTORY, TenantPolicyAction.VIEW_ANALYTICS, TenantPolicyAction.VIEW_CHANNEL_MESSAGES));
    ALLOWED_ACTIONS.put(ActorRole.SYSTEM_CONNECTOR_WORKER, EnumSet.of(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND, TenantPolicyAction.VIEW_CONNECTOR_COMMANDS));
  }

  public TenantPolicyDecision evaluate(TenantPolicyContext context) {
    TenantPolicyDecision structural = validateStructure(context);
    if (!structural.allowed()) {
      return structural;
    }
    if (context.action() == TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND) {
      return evaluateConnectorExecution(context);
    }
    if (sensitiveDeniedByDefault(context)) {
      return TenantPolicyDecision.deny(context, "SENSITIVE_ACTION_DENIED", "Action requires a role with explicit sensitive-action permission");
    }
    boolean allowed = context.actorRoles().stream()
        .map(ALLOWED_ACTIONS::get)
        .filter(actions -> actions != null && !actions.isEmpty())
        .anyMatch(actions -> actions.contains(context.action()));
    if (!allowed) {
      return TenantPolicyDecision.deny(context, "ACTION_NOT_ALLOWED", "Role is not allowed to perform action");
    }
    return TenantPolicyDecision.allow(context, "ROLE_ACTION_ALLOWED");
  }

  public void requireAllowed(TenantPolicyContext context) {
    TenantPolicyDecision decision = evaluate(context);
    if (!decision.allowed()) {
      throw new TenantPolicyException(decision.message());
    }
  }

  private TenantPolicyDecision validateStructure(TenantPolicyContext context) {
    if (context == null) {
      return TenantPolicyDecision.deny(null, "MISSING_CONTEXT", "Tenant policy context is required");
    }
    if (context.tenantId() == null || context.targetTenantId() == null) {
      return TenantPolicyDecision.deny(context, "MISSING_TENANT", "Tenant id and target tenant id are required");
    }
    if (!context.tenantId().equals(context.targetTenantId())) {
      return TenantPolicyDecision.deny(context, "TENANT_MISMATCH", "Actor tenant must match target tenant");
    }
    if (context.action() == null) {
      return TenantPolicyDecision.deny(context, "MISSING_ACTION", "Action is required");
    }
    if ((context.actorRoles() == null || context.actorRoles().isEmpty())) {
      return TenantPolicyDecision.deny(context, "UNSUPPORTED_ROLE", "At least one supported actor role is required");
    }
    boolean hasSupportedRole = context.actorRoles().stream().anyMatch(ALLOWED_ACTIONS::containsKey);
    if (!hasSupportedRole) {
      return TenantPolicyDecision.deny(context, "UNSUPPORTED_ROLE", "Unsupported actor role");
    }
    if (context.actorId() == null && !context.systemActor()) {
      return TenantPolicyDecision.deny(context, "MISSING_ACTOR", "Actor id is required unless a valid system actor is represented");
    }
    if (context.systemActor() && !context.actorRoles().contains(ActorRole.SYSTEM_CONNECTOR_WORKER)) {
      return TenantPolicyDecision.deny(context, "INVALID_SYSTEM_ACTOR", "System actor must use a system role");
    }
    return TenantPolicyDecision.allow(context, "STRUCTURE_VALID");
  }

  private TenantPolicyDecision evaluateConnectorExecution(TenantPolicyContext context) {
    if (!context.systemActor() || !context.actorRoles().contains(ActorRole.SYSTEM_CONNECTOR_WORKER)) {
      return TenantPolicyDecision.deny(context, "CONNECTOR_EXECUTION_HUMAN_DENIED", "Human roles cannot execute connector commands in Stage 10H");
    }
    if (!context.approved() || context.connectorCommandState() != ConnectorCommandExecutionState.EXECUTION_READY) {
      return TenantPolicyDecision.deny(context, "CONNECTOR_NOT_READY", "Connector command must be approved and ready");
    }
    if (context.executionMode() != ExecutionMode.DRY_RUN) {
      return TenantPolicyDecision.deny(context, "REAL_EXECUTION_DISABLED_STAGE_10H", "Stage 10H permits connector execution policy only for dry-run mode");
    }
    return TenantPolicyDecision.allow(context, "SYSTEM_CONNECTOR_WORKER_ALLOWED");
  }

  private boolean sensitiveDeniedByDefault(TenantPolicyContext context) {
    if (context.actorRoles().contains(ActorRole.OWNER_ADMIN)) {
      return false;
    }
    if (context.action() == TenantPolicyAction.APPROVE_BELOW_MARGIN_DISCOUNT) {
      return true;
    }
    if (context.action() == TenantPolicyAction.APPROVE_RISKY_SUBSTITUTE) {
      return true;
    }
    if (context.action() == TenantPolicyAction.APPROVE_COMPENSATION_PLAN) {
      return !context.actorRoles().contains(ActorRole.INTEGRATION_ADMIN);
    }
    return false;
  }
}
