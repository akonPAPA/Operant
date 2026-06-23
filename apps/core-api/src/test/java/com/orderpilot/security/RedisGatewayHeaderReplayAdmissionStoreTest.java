package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisGatewayHeaderReplayAdmissionStoreTest {
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
  private static final String NONCE = "nonce-replay-test";
  private static final Duration TTL = Duration.ofSeconds(600);

  @Test
  void redisReplayStoreUsesAtomicSetIfAbsent() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(any(), eq("1"), eq(TTL))).thenReturn(true, false);

    RedisGatewayHeaderReplayAdmissionStore store =
        new RedisGatewayHeaderReplayAdmissionStore(redisTemplate, "op:gw-replay");

    assertThat(store.admitFirstUse(TENANT, ACTOR, NONCE, TTL)).isTrue();
    assertThat(store.admitFirstUse(TENANT, ACTOR, NONCE, TTL)).isFalse();
  }

  @Test
  void redisReplayStoreFailsClosedOnRedisException() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(any(), eq("1"), eq(TTL)))
        .thenThrow(new RedisConnectionFailureException("test redis unavailable"));

    RedisGatewayHeaderReplayAdmissionStore store =
        new RedisGatewayHeaderReplayAdmissionStore(redisTemplate, "op:gw-replay");

    assertThat(store.admitFirstUse(TENANT, ACTOR, NONCE, TTL)).isFalse();
  }

  @Test
  void redisReplayStoreDoesNotExposeSensitiveKeyMaterial() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(any(), eq("1"), eq(TTL))).thenReturn(true);

    RedisGatewayHeaderReplayAdmissionStore store =
        new RedisGatewayHeaderReplayAdmissionStore(redisTemplate, "op:gw-replay");
    assertThat(store.admitFirstUse(TENANT, ACTOR, NONCE, TTL)).isTrue();

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(valueOperations).setIfAbsent(key.capture(), eq("1"), eq(TTL));
    assertThat(key.getValue())
        .startsWith("op:gw-replay:")
        .doesNotContain(TENANT)
        .doesNotContain(ACTOR)
        .doesNotContain(NONCE)
        .doesNotContain("signature")
        .doesNotContain("canonical")
        .doesNotContain("secret");
    assertThat(key.getValue().substring("op:gw-replay:".length())).hasSize(64);
  }
}
