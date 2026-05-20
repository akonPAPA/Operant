package com.orderpilot.application.services.channel;

import java.util.List;

public interface ChannelAdapter<T> {
  ChannelType channelType();
  List<NormalizedInboundMessage> normalize(T payload);
}