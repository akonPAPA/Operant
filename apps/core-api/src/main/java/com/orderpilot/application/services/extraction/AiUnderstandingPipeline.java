package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.domain.extraction.ExtractionRun;

public interface AiUnderstandingPipeline {
  ExtractionRun runNow(ExtractionRunRequest request);
}
