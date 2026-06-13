package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-16K — unit tests for {@link SignedActorVerifier}: HMAC-SHA-256 acceptance and the full
 * rejection matrix (missing/invalid/stale/malformed), plus the inert (no secret) mode.
 */
class SignedActorVerifierStage16KTest {
  private static final String SECRET = "test-actor-signing-secret";
  private static final long SKEW = 300L;
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private SignedActorVerifier verifier() {
    return new SignedActorVerifier(SECRET, SKEW, CLOCK);
  }

  private static String sign(long timestamp) {
    return SignedActorVerifier.hmacHex(SECRET, TENANT + "\n" + ACTOR + "\n" + timestamp);
  }

  @Test
  void notConfiguredWhenSecretBlank() {
    SignedActorVerifier v = new SignedActorVerifier("", SKEW, CLOCK);
    assertThatCode(() -> v.verify(TENANT, ACTOR, null, null)).doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThat(v.isConfigured()).isFalse();
  }

  @Test
  void validSignatureAccepted() {
    long ts = NOW.getEpochSecond();
    assertThatCode(() -> verifier().verify(TENANT, ACTOR, sign(ts), Long.toString(ts)))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidSignatureRejected() {
    long ts = NOW.getEpochSecond();
    assertThatThrownBy(() -> verifier().verify(TENANT, ACTOR, sign(ts) + "00", Long.toString(ts)))
        .isInstanceOf(ActorVerificationException.class);
  }

  @Test
  void wrongActorSignatureRejected() {
    long ts = NOW.getEpochSecond();
    // Signature was computed for ACTOR; verifying against a different actor must fail.
    assertThatThrownBy(() -> verifier().verify(TENANT, UUID.randomUUID(), sign(ts), Long.toString(ts)))
        .isInstanceOf(ActorVerificationException.class);
  }

  @Test
  void missingSignatureRejectedWhenConfigured() {
    long ts = NOW.getEpochSecond();
    assertThatThrownBy(() -> verifier().verify(TENANT, ACTOR, null, Long.toString(ts)))
        .isInstanceOf(ActorVerificationException.class);
  }

  @Test
  void missingTimestampRejected() {
    long ts = NOW.getEpochSecond();
    assertThatThrownBy(() -> verifier().verify(TENANT, ACTOR, sign(ts), null))
        .isInstanceOf(ActorVerificationException.class);
  }

  @Test
  void staleTimestampRejected() {
    long ts = NOW.getEpochSecond() - (SKEW + 60L);
    assertThatThrownBy(() -> verifier().verify(TENANT, ACTOR, sign(ts), Long.toString(ts)))
        .isInstanceOf(ActorVerificationException.class);
  }

  @Test
  void malformedTimestampRejected() {
    long ts = NOW.getEpochSecond();
    assertThatThrownBy(() -> verifier().verify(TENANT, ACTOR, sign(ts), "not-a-number"))
        .isInstanceOf(ActorVerificationException.class);
  }
}
