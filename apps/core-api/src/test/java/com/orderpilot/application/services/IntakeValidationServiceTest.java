package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class IntakeValidationServiceTest {
  private final IntakeValidationService service = new IntakeValidationService();
  @Test void rejectsInvalidFileType(){ assertThatThrownBy(() -> service.validateFile("application/x-msdownload", 100)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported"); }
  @Test void rejectsEmptyFile(){ assertThatThrownBy(() -> service.validateFile("application/pdf", 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be empty"); }
  @Test void rejectsEmptyMessage(){ assertThatThrownBy(() -> service.validateMessage("TELEGRAM", "", false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("textContent"); }
}