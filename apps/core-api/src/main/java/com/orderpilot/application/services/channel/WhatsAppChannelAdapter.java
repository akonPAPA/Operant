package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppChannelAdapter extends Stage12ChannelAdapterSupport {
  public WhatsAppChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.WHATSAPP, ChannelType.WHATSAPP, objectMapper); }
}
