package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class FileUploadChannelAdapter extends Stage12ChannelAdapterSupport {
  public FileUploadChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.FILE_UPLOAD, ChannelType.WEB_UPLOAD, objectMapper); }
}
