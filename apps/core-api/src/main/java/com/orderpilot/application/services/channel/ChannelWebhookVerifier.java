package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.Map;

/**
 * Contract for Path-2 (connection-based) channel webhook verification.
 *
 * <p><b>OP-CAP-42J / OP-CAP-42K — raw-body verification contract.</b> For any provider that performs
 * <b>real cryptographic</b> verification (an HMAC/signature over the request body, e.g. Meta-Messenger's
 * {@code X-Hub-Signature-256}), the {@code rawPayload} passed to {@link #verify} MUST be the
 * <b>byte-exact</b> request body as received on the wire. Provider signatures are computed over those
 * exact bytes, so any canonicalization — parsing to {@code JsonNode}/{@code Map} and re-serializing via
 * Jackson ({@code ChannelEventNormalizationService.toJson(payload)}) — changes the bytes (whitespace,
 * key order, escaping) and silently breaks byte-exact verification. That was the OP-CAP-42J bug.
 *
 * <p>Therefore:
 * <ul>
 *   <li>Canonical/parsed JSON is allowed only for <b>normalization and persistence after</b> verification
 *       has already succeeded — never as the signed input for a real cryptographic provider.</li>
 *   <li>The raw-body entry point that satisfies this contract is
 *       {@code ChannelEventNormalizationService.normalize(UUID, ChannelProviderType, String, Map)}, fed by
 *       the controller's raw {@code @RequestBody String} (see {@code ChannelWebhookController.metaMessenger}).
 *       The canonical {@code normalize(..., Object payload, ...)} overload must NOT be used to feed signed
 *       input to a real cryptographic verifier.</li>
 *   <li>Non-cryptographic / fail-closed stub verifiers ({@link AbstractProviderWebhookVerifier}: Telegram,
 *       WhatsApp Path-2, Viber, WeChat) are byte-agnostic — they reject on the mere presence of an
 *       unverified header — so they may receive either form without weakening fail-closed behaviour.</li>
 * </ul>
 *
 * <p>Implementations must never echo the signature, secret, or raw body in the returned
 * {@link VerificationResult}.
 */
public interface ChannelWebhookVerifier {
  ChannelProviderType providerType();

  /**
   * Verify the inbound webhook. For real cryptographic providers {@code rawPayload} is the byte-exact wire
   * body (see the type-level raw-body contract); it must never be a canonical re-serialization of a parsed
   * payload.
   */
  VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload);
}
