package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class ApiChannelAdapter extends Stage12ChannelAdapterSupport {
  public ApiChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.API, ChannelType.API, objectMapper); }
}
