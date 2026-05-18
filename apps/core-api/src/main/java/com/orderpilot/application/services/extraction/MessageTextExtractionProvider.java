package com.orderpilot.application.services.extraction;

import com.orderpilot.domain.intake.ChannelMessageRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MessageTextExtractionProvider implements TextExtractionProvider {
  private final ChannelMessageRepository repository;
  public MessageTextExtractionProvider(ChannelMessageRepository repository){this.repository=repository;}
  public boolean supports(String sourceType){return "CHANNEL_MESSAGE".equals(sourceType);}
  public TextExtractionOutput extractText(UUID tenantId, String sourceType, UUID sourceId){
    var message = repository.findByIdAndTenantId(sourceId, tenantId).orElseThrow(() -> new IllegalArgumentException("Channel message not found"));
    return new TextExtractionOutput(message.getTextContent()==null ? "" : message.getTextContent(), "MESSAGE_TEXT", null, null, 0.90);
  }
  public String providerName(){return "message-text";}
}