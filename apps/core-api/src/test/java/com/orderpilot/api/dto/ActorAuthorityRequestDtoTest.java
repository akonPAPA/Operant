package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage10BDtos.HumanCorrectionRequest;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityLinkRequest;
import com.orderpilot.api.dto.Stage11ADtos.LegacyDraftQuoteCreateRequest;
import com.orderpilot.api.dto.Stage11ADtos.LegacyQuoteLifecycleRequest;
import com.orderpilot.api.dto.Stage11ADtos.LegacySubstituteDecisionRequest;
import com.orderpilot.api.dto.Stage11EDtos.LegacyChangeRequestCancelRequest;
import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqRequest;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionRequest;
import com.orderpilot.api.dto.Stage7Dtos.MarkBotResponseReadyRequest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ActorAuthorityRequestDtoTest {
  private static final Set<String> FORBIDDEN_AUTHORITY_FIELDS = Set.of(
      "actorId",
      "actorUserId",
      "actorRole",
      "linkedByUserId",
      "createdBy",
      "createdByUserId",
      "reviewedBy",
      "approvedBy",
      "rejectedBy",
      "decidedBy",
      "executedBy",
      "correctedByUserId",
      "approvalStatus",
      "executionStatus",
      "externalWriteAuthority",
      "supportGrantId",
      "staffUserId");

  @Test
  void publicRequestRecordsContainBusinessIntentOnly() {
    List<Class<?>> publicRequestTypes = List.of(
        MarkBotResponseReadyRequest.class,
        HumanCorrectionRequest.class,
        ChannelIdentityLinkRequest.class,
        LegacyDraftQuoteCreateRequest.class,
        LegacySubstituteDecisionRequest.class,
        LegacyQuoteLifecycleRequest.class,
        LegacyChangeRequestCancelRequest.class,
        CreateDraftQuoteFromRfqRequest.class,
        QuoteApprovalDecisionRequest.class);

    for (Class<?> requestType : publicRequestTypes) {
      assertThat(componentNames(requestType))
          .as(requestType.getSimpleName())
          .doesNotContainAnyElementsOf(FORBIDDEN_AUTHORITY_FIELDS);
    }
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
