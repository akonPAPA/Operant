package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteRequest;
import com.orderpilot.api.dto.Stage12BDtos.QuoteSourceContextDto;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

// OP-CAP-31: the default operator source-summary response must not carry internal identifiers, and
// the public conversion request must not carry actor authority. These are contract-shape proofs:
// Spring binds responses/requests by record component, so a field that does not exist cannot leak
// or be spoofed.
class QuoteSourceContextResponseLeakTest {

  private static List<String> componentsOf(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  @Test
  void sourceContextDefaultResponseDoesNotExposeInternalIdentifiers() {
    List<String> components = componentsOf(QuoteSourceContextDto.class);
    assertThat(components).doesNotContain(
        "sourceId",
        "conversionAttemptId",
        "triggeredBy",
        "createdByType",
        "metadata",
        "candidateLines",
        "sourceEvidenceId",
        "auditEventIds");
  }

  @Test
  void sourceContextDefaultResponseStillExposesOperatorSafeBusinessFields() {
    List<String> components = componentsOf(QuoteSourceContextDto.class);
    assertThat(components).contains(
        "sourceType",
        "sourceChannel",
        "sourceExternalRef",
        "sourceReceivedAt",
        "conversionStatus",
        "candidateLineCount",
        "reviewRequired",
        "validationIssues");
  }

  @Test
  void channelToQuoteRequestDoesNotAcceptActorAuthority() {
    List<String> components = componentsOf(ChannelToQuoteRequest.class);
    assertThat(components).doesNotContain("actorId", "actorType", "actorRole", "tenantId");
  }
}
