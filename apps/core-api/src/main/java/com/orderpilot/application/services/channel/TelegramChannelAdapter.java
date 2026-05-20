package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class TelegramChannelAdapter extends Stage12ChannelAdapterSupport {
  public TelegramChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.TELEGRAM, ChannelType.TELEGRAM, objectMapper); }
}
