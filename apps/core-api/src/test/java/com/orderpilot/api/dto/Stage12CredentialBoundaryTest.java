package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12Dtos.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Stage12CredentialBoundaryTest {
  private static final Set<String> FORBIDDEN = Set.of(
      "secretvalue", "secretref", "secretreferenceid", "credential", "credentials", "token",
      "payloadjson", "requestpayloadjson", "payloadhash", "idempotencykey",
      "auditcorrelationid", "errormessage", "sourceactorexternalid", "externaleventid");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void connectionCreateRequestsContainBusinessIntentOnly() {
    assertNoForbiddenComponents(ChannelConnectionRequest.class);
    assertNoForbiddenComponents(IntegrationConnectionRequest.class);
  }

  @Test
  void publicStage12ResponsesContainNoSecretPayloadOrRawErrorFields() {
    assertNoForbiddenComponents(ChannelConnectionResponse.class);
    assertNoForbiddenComponents(IntegrationConnectionResponse.class);
    assertNoForbiddenComponents(InboundChannelEventResponse.class);
    assertNoForbiddenComponents(ConnectorSyncEventResponse.class);
  }

  @Test
  void credentialValueIsDeserializableButNeverSerializable() throws Exception {
    assertThat(objectMapper.readValue("{\"secretValue\":\"top-secret\"}", SecretConfigurationRequest.class).secretValue())
        .isEqualTo("top-secret");
    assertThat(objectMapper.writeValueAsString(new SecretConfigurationRequest("top-secret")))
        .doesNotContain("top-secret", "secretValue");
  }

  private static void assertNoForbiddenComponents(Class<?> recordType) {
    Set<String> names = Arrays.stream(recordType.getRecordComponents())
        .map(component -> component.getName().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    assertThat(names)
        .as("%s must not expose credential authority or internal payload fields", recordType.getSimpleName())
        .doesNotContainAnyElementsOf(FORBIDDEN);
  }
}
