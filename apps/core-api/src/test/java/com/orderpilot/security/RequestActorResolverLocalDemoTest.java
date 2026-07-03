package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestActorResolverLocalDemoTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);
  private static final UUID TENANT_ID = UUID.randomUUID();

  @Test
  void headerlessUnsignedLocalDemoRequestUsesBackendOwnedOperator() {
    RequestActorResolver resolver = resolver("", environment("local"));

    UUID actorId =
        resolver.resolveVerifiedLocalDemoOperator(new MockHttpServletRequest(), TENANT_ID);

    assertThat(actorId).isEqualTo(RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR);
    assertThat(actorId).isNotEqualTo(RequestActorResolver.SYSTEM_ACTOR);
  }

  @Test
  void headerlessUnsignedDocumentedDefaultRuntimeUsesBackendOwnedOperator() {
    RequestActorResolver resolver = resolver("", new MockEnvironment());

    UUID actorId =
        resolver.resolveVerifiedLocalDemoOperator(new MockHttpServletRequest(), TENANT_ID);

    assertThat(actorId).isEqualTo(RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR);
  }

  @Test
  void signedProductionModeWithoutActorStillFailsClosed() {
    RequestActorResolver resolver = resolver("production-secret", environment("production"));

    assertThatThrownBy(
            () ->
                resolver.resolveVerifiedLocalDemoOperator(
                    new MockHttpServletRequest(), TENANT_ID))
        .isInstanceOf(ActorVerificationException.class)
        .hasMessage("Actor identity is required");
  }

  @Test
  void explicitSystemActorIsNeverReclassifiedAsLocalDemoOperator() {
    RequestActorResolver resolver = resolver("", environment("demo"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        RequestActorResolver.ACTOR_HEADER, RequestActorResolver.SYSTEM_ACTOR.toString());

    UUID actorId = resolver.resolveVerifiedLocalDemoOperator(request, TENANT_ID);

    assertThat(actorId).isEqualTo(RequestActorResolver.SYSTEM_ACTOR);
  }

  @Test
  void unsignedProductionLikeRuntimeDoesNotReceiveLocalDemoOperator() {
    RequestActorResolver resolver = resolver("", environment("staging"));

    UUID actorId =
        resolver.resolveVerifiedLocalDemoOperator(new MockHttpServletRequest(), TENANT_ID);

    assertThat(actorId).isEqualTo(RequestActorResolver.SYSTEM_ACTOR);
  }

  private static RequestActorResolver resolver(
      String signingSecret, MockEnvironment environment) {
    return new RequestActorResolver(signingSecret, 300, CLOCK, environment);
  }

  private static MockEnvironment environment(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return environment;
  }
}
