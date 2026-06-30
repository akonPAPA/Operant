package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.integration.ConnectorCredentialRef;
import com.orderpilot.domain.integration.CredentialStatus;
import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;
import jakarta.persistence.Entity;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class ConnectorCredentialEntitySerializationTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final Instant now = Instant.parse("2026-06-30T00:00:00Z");

  @Test
  void channelConnectionDoesNotSerializeSecretReferences() throws Exception {
    ChannelConnection connection = new ChannelConnection(
        UUID.randomUUID(), ChannelProviderType.TELEGRAM, "Telegram", "account", "https://example.test/hook",
        "vault://channel/credential", now);

    assertThat(objectMapper.writeValueAsString(connection))
        .doesNotContain("secretRef", "secretReferenceId", "vault://channel/credential");
  }

  @Test
  void integrationConnectionDoesNotSerializeSecretReferences() throws Exception {
    IntegrationConnection connection = new IntegrationConnection(
        UUID.randomUUID(), IntegrationProviderType.CSV, "CSV import", "MANUAL_UPLOAD",
        "vault://integration/credential", null, now);

    assertThat(objectMapper.writeValueAsString(connection))
        .doesNotContain("secretRef", "secretReferenceId", "vault://integration/credential");
  }

  @Test
  void connectorCredentialRefDoesNotSerializeSecretReference() throws Exception {
    ConnectorCredentialRef credential = new ConnectorCredentialRef(
        UUID.randomUUID(), UUID.randomUUID(), "vault://connector/credential",
        CredentialStatus.CONFIGURED_PLACEHOLDER, now);

    assertThat(objectMapper.writeValueAsString(credential))
        .doesNotContain("secretRef", "vault://connector/credential");
  }

  @Test
  void credentialBearingEntityGettersAreIgnoredByJackson() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    List<String> inspected = new ArrayList<>();
    List<String> violations = new ArrayList<>();

    for (var bean : scanner.findCandidateComponents("com.orderpilot.domain")) {
      Class<?> entity = Class.forName(bean.getBeanClassName());
      for (Method method : entity.getDeclaredMethods()) {
        if (method.getParameterCount() != 0 || !isCredentialGetter(method.getName())) {
          continue;
        }
        String getter = entity.getSimpleName() + "." + method.getName();
        inspected.add(getter);
        if (!method.isAnnotationPresent(JsonIgnore.class)) {
          violations.add(getter);
        }
      }
    }

    assertThat(inspected).as("credential-bearing entity getters discovered").isNotEmpty();
    assertThat(violations).as("credential-bearing entity getters must use @JsonIgnore").isEmpty();
  }

  private static boolean isCredentialGetter(String methodName) {
    String normalized = methodName.toLowerCase(Locale.ROOT);
    return normalized.contains("secretref")
        || normalized.contains("secretreference")
        || normalized.contains("clientsecret")
        || normalized.contains("accesstoken")
        || normalized.contains("refreshtoken")
        || normalized.contains("password")
        || normalized.contains("apikey")
        || normalized.contains("privatekey");
  }
}
