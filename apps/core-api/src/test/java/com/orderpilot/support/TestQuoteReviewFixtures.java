package com.orderpilot.support;

import java.util.UUID;

public final class TestQuoteReviewFixtures {
  private TestQuoteReviewFixtures() {}

  public static final UUID CHANNEL_MESSAGE_A = UUID.fromString("60000000-0000-4000-8000-000000000001");
  public static final UUID CHANNEL_MESSAGE_B = UUID.fromString("60000000-0000-4000-8000-000000000002");
  public static final UUID INBOUND_DOCUMENT_A = UUID.fromString("70000000-0000-4000-8000-000000000001");
  public static final UUID DRAFT_QUOTE_A = UUID.fromString("80000000-0000-4000-8000-000000000001");
  public static final UUID ATTEMPT_A_REVIEW = UUID.fromString("90000000-0000-4000-8000-000000000001");
  public static final UUID ATTEMPT_A_LINKED = UUID.fromString("90000000-0000-4000-8000-000000000002");
  public static final UUID ATTEMPT_B_REVIEW = UUID.fromString("90000000-0000-4000-8000-000000000003");
}
