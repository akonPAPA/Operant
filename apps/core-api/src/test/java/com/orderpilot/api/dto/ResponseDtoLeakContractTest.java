package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryRecordDto;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.EvaluationRunDto;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.CommandCenterDtos.AuditTimelineItemDto;
import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterSummaryDto;
import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.OperatorCorrectionLearningRecordDto;
import com.orderpilot.api.dto.Stage6Dtos.NoteReview;
import com.orderpilot.api.dto.Stage6Dtos.IssueReview;
import com.orderpilot.api.dto.Stage6Dtos.SuggestedActionReview;
import com.orderpilot.api.dto.Stage7Dtos.BotResponseDraftResponse;
import com.orderpilot.api.dto.Stage10BDtos.HumanCorrectionResponse;
import com.orderpilot.api.dto.Stage10CDtos.ChangeRequestResponse;
import com.orderpilot.api.dto.Stage10CDtos.OutboxEventResponse;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityResponse;
import com.orderpilot.api.dto.Stage12CDtos.AuditTimelineEvent;
import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffResponse;
import com.orderpilot.api.dto.Stage12ADtos.ApprovalDecision;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.api.dto.TrustAiProjectionDtos.TrustAiDomainEventDto;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewDtos.AuditTimelineItem;
import com.orderpilot.api.rest.ControllerEntityReturnBanTest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Wave 01I Category D guard. The Stage 11E quote-handoff and channel RFQ-handoff response DTOs are
 * operator-safe: they must never expose internal integrity/dedupe internals, raw payloads, internal
 * actor ids, or raw source/correlation ids. This is a fast reflection check (no Spring context).
 */
class ResponseDtoLeakContractTest {
  private static final Set<String> GLOBAL_FORBIDDEN_RESPONSE_FIELDS = Set.of(
      "payloadJson",
      "requestPayloadJson",
      "payloadHash",
      "idempotencyKey",
      "connectorIdempotencyKeyHash",
      "auditCorrelationId",
      "generatedBy",
      "createdBy",
      "createdByUserId",
      "reviewedBy",
      "decidedBy",
      "approvedBy",
      "actorId",
      "actorRole",
      "userId",
      "linkedByUserId",
      "correctedByUserId",
      "reviewerUserId",
      "secretRef",
      "secretValue",
      "secretReferenceId",
      "snapshotId",
      "failureReason",
      "lastError",
      "executionStatus",
      "externalExecutionStatus");

  private static final Set<String> FORBIDDEN_RESPONSE_FIELDS = Set.of(
      "payloadJson",
      "requestPayloadJson",
      "payloadHash",
      "idempotencyKey",
      "connectorIdempotencyKeyHash",
      "auditCorrelationId",
      "generatedBy",
      "createdBy",
      "createdByUserId",
      "reviewedBy",
      "decidedBy",
      "approvedBy",
      "actorId",
      "actorRole",
      "userId",
      "linkedByUserId",
      "correctedByUserId",
      "reviewerUserId",
      "secretRef",
      "secretValue",
      "secretReferenceId",
      "snapshotId",
      "failureReason",
      "lastError",
      "executionStatus",
      "externalExecutionStatus",
      "sourceExternalEventId",
      "inboundChannelEventId",
      "channelConnectionId");

  @Test
  void everyPublicControllerResponseIsFreeOfForbiddenInternalFields() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Class<?> controller : ControllerEntityReturnBanTest.controllerClasses()) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        collectForbiddenResponseFields(
            method.getGenericReturnType(),
            controller.getSimpleName() + "." + method.getName(),
            new HashSet<>(),
            violations);
      }
    }

    assertThat(violations)
        .as("Public controller response DTOs must not expose internal authority, payload, audit, or secret fields")
        .isEmpty();
  }

  @Test
  void repairedResponseGroupsSerializeWithoutForbiddenFields() throws Exception {
    List<Class<?>> repairedTypes = List.of(
        AiMemoryRecordDto.class,
        EvaluationRunDto.class,
        OperatorCorrectionLearningRecordDto.class,
        NoteReview.class,
        BotResponseDraftResponse.class,
        ChangeRequestResponse.class,
        CommandCenterSummaryDto.class,
        AuditTimelineItemDto.class,
        TrustAiDomainEventDto.class,
        AuditTimelineItem.class,
        ValidationReviewActionResult.class);
    Set<String> forbidden = new HashSet<>(GLOBAL_FORBIDDEN_RESPONSE_FIELDS);
    forbidden.add("tenantId");

    ObjectMapper mapper = new ObjectMapper();
    for (Class<?> responseType : repairedTypes) {
      Object response = emptyRecord(responseType);
      Set<String> jsonFields = new HashSet<>();
      mapper.readTree(mapper.writeValueAsString(response)).fieldNames().forEachRemaining(jsonFields::add);
      assertThat(jsonFields)
          .as(responseType.getSimpleName())
          .doesNotContainAnyElementsOf(forbidden);
    }
  }

  @Test
  void quoteHandoffResponseExposesOnlySafeBusinessFields() {
    Set<String> names = componentNames(QuoteHandoffResponse.class);
    assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(names).contains(
        "quoteId", "handoffReadinessStatus", "hasSnapshot", "changeRequestId",
        "externalExecutionEnabled", "allowedActions");
  }

  @Test
  void stage11eDeclaresNoSnapshotResponseAndNoResponseRecordLeaks() {
    for (Class<?> nested : Stage11EDtos.class.getDeclaredClasses()) {
      // The unsafe (payloadJson/generatedBy/payloadHash) snapshot response DTO is removed for good.
      assertThat(nested.getSimpleName()).isNotEqualTo("QuoteHandoffSnapshotResponse");
      if (nested.isRecord() && nested.getSimpleName().endsWith("Response")) {
        assertThat(componentNames(nested))
            .as(nested.getSimpleName())
            .doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
      }
    }
  }

  @Test
  void stage12aApprovalResponsesExposeOnlySafeBusinessFields() {
    assertThat(componentNames(QuoteTransactionResponse.class)).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);

    Set<String> state = componentNames(QuoteApprovalStateResponse.class);
    assertThat(state).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(state).contains(
        "quoteId", "status", "approvalRequired", "changeRequestId", "externalExecutionEnabled");

    Set<String> command = componentNames(QuoteApprovalCommandResponse.class);
    assertThat(command).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(command).contains(
        "quoteId", "previousStatus", "newStatus", "approvalDecision", "externalExecutionEnabled");

    Set<String> decision = componentNames(ApprovalDecision.class);
    assertThat(decision).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(decision).contains("decision", "comment", "decidedAt", "previousQuoteStatus", "newQuoteStatus");
  }

  @Test
  void channelRfqHandoffResponseExposesOnlyOperatorSafeFields() {
    Set<String> names = componentNames(ChannelRfqHandoffResponse.class);
    assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(names).contains(
        "sourceChannel", "sourceActorExternalId", "requestPreview", "status", "detectedIntent");
  }

  @Test
  void repairedResponsesExposeNoRawActorSourceErrorOrAuditDetailFields() {
    assertThat(componentNames(HumanCorrectionResponse.class))
        .doesNotContain("correctedByUserId");
    assertThat(componentNames(ChannelIdentityResponse.class))
        .doesNotContain("linkedByUserId");
    assertThat(componentNames(BotResponseDraftResponse.class))
        .doesNotContain("sourceMessageId", "reviewedBy");
    assertThat(componentNames(ChangeRequestResponse.class))
        .doesNotContain("sourceId", "failureReason");
    assertThat(componentNames(OutboxEventResponse.class))
        .doesNotContain("id", "aggregateId", "payloadJson", "attemptCount", "lastError");
    assertThat(componentNames(IssueReview.class))
        .doesNotContain("detailsJson");
    assertThat(componentNames(SuggestedActionReview.class))
        .doesNotContain("suggestionJson");
    assertThat(componentNames(AuditTimelineEvent.class))
        .doesNotContain("metadata");
    assertThat(componentNames(AuditTimelineItemDto.class))
        .doesNotContain("entityId", "actorId");
    assertThat(componentNames(AuditTimelineItem.class))
        .doesNotContain("entityId", "actorId");
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }

  private static void collectForbiddenResponseFields(
      Type type, String path, Set<Type> visited, List<String> violations) {
    if (!visited.add(type)) {
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectForbiddenResponseFields(argument, path, visited, violations);
      }
      return;
    }
    if (!(type instanceof Class<?> clazz)
        || !clazz.getName().startsWith("com.orderpilot.api.dto.")) {
      return;
    }

    if (clazz.isRecord()) {
      for (RecordComponent component : clazz.getRecordComponents()) {
        inspectResponseProperty(component.getName(), component.getGenericType(), path, visited, violations);
      }
      return;
    }

    for (var field : clazz.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        inspectResponseProperty(field.getName(), field.getGenericType(), path, visited, violations);
      }
    }
  }

  private static void inspectResponseProperty(
      String name, Type type, String path, Set<Type> visited, List<String> violations) {
    String propertyPath = path + " -> " + name;
    if (GLOBAL_FORBIDDEN_RESPONSE_FIELDS.contains(name)
        && !isScreenRequiredInternalSupportField(path, name)) {
      violations.add(propertyPath);
    }
    collectForbiddenResponseFields(type, propertyPath, visited, violations);
  }

  private static boolean isScreenRequiredInternalSupportField(String path, String name) {
    return "executionStatus".equals(name)
        && path.startsWith("InternalSupportController.");
  }

  private static Object emptyRecord(Class<?> recordType) throws ReflectiveOperationException {
    RecordComponent[] components = recordType.getRecordComponents();
    Class<?>[] parameterTypes = Arrays.stream(components)
        .map(RecordComponent::getType)
        .toArray(Class<?>[]::new);
    Object[] arguments = Arrays.stream(parameterTypes)
        .map(ResponseDtoLeakContractTest::defaultValue)
        .toArray();
    return recordType.getDeclaredConstructor(parameterTypes).newInstance(arguments);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    return 0D;
  }
}
