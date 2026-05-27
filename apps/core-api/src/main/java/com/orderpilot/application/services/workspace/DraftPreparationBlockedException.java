package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage6Dtos.BlockingReason;
import java.util.List;

public class DraftPreparationBlockedException extends IllegalStateException {
  private final List<BlockingReason> blockingReasons;

  public DraftPreparationBlockedException(List<BlockingReason> blockingReasons) {
    super(blockingReasons.isEmpty() ? "Draft preparation is blocked" : blockingReasons.get(0).reason());
    this.blockingReasons = List.copyOf(blockingReasons);
  }

  public List<BlockingReason> getBlockingReasons() {
    return blockingReasons;
  }
}
