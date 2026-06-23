package com.orderpilot.security;

import java.time.Duration;

/**
 * Minimal first-use admission port for gateway-signed authority header nonces.
 *
 * <p>Implementations must be atomic for a single replay key: exactly one caller may admit a
 * tenant/actor/nonce tuple within the supplied TTL. Returning {@code false} fails authentication.
 */
interface GatewayHeaderReplayAdmissionStore {

  boolean admitFirstUse(String tenantId, String actorId, String nonce, Duration ttl);
}
