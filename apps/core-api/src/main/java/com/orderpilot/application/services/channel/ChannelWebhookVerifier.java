package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.Map;

public interface ChannelWebhookVerifier {
  ChannelProviderType providerType();
  VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload);
}
