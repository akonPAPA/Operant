package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class WeChatChannelAdapter extends Stage12ChannelAdapterSupport {
  public WeChatChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.WECHAT, ChannelType.API, objectMapper); }
}
