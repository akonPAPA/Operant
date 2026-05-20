package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.List;

public interface ChannelAdapter<T> {
  ChannelType channelType();
  List<NormalizedInboundMessage> normalize(T payload);

  default ChannelProviderType providerType() {
    if (channelType() == ChannelType.WEB_UPLOAD) {
      return ChannelProviderType.FILE_UPLOAD;
    }
    if (channelType() == ChannelType.VIBER_STUB) {
      return ChannelProviderType.VIBER;
    }
    if (channelType() == ChannelType.MESSENGER_STUB) {
      return ChannelProviderType.META_MESSENGER;
    }
    return ChannelProviderType.valueOf(channelType().name());
  }

  default NormalizedChannelEvent normalizeInbound(Object providerPayload, ChannelConnection connection) {
    throw new UnsupportedOperationException("Stage 12 normalized event adapter is not implemented for " + providerType());
  }

  default ChannelHealthCheckResult healthCheck(ChannelConnection connection) {
    return new ChannelHealthCheckResult(providerType(), true, "ADAPTER_READY_STUB", "No external network call performed");
  }
}
