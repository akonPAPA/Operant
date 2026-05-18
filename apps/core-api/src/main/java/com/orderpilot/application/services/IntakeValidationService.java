package com.orderpilot.application.services;

import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class IntakeValidationService {
  public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L;
  private static final Set<String> ALLOWED = Set.of("application/pdf","text/csv","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","image/png","image/jpeg","text/plain");
  public void validateFile(String contentType, long sizeBytes) {
    if (sizeBytes <= 0) throw new IllegalArgumentException("Uploaded file must not be empty");
    if (sizeBytes > DEFAULT_MAX_FILE_BYTES) throw new IllegalArgumentException("Uploaded file exceeds max size");
    if (contentType == null || !ALLOWED.contains(contentType)) throw new IllegalArgumentException("Unsupported content type");
  }
  public void validateMessage(String channel, String textContent, boolean hasAttachmentMetadata) {
    if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel is required");
    if ((textContent == null || textContent.isBlank()) && !hasAttachmentMetadata) throw new IllegalArgumentException("textContent or attachment metadata is required");
  }
}