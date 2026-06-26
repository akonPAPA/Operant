package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingEventDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorFulfillmentTimelineResponse;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicTrackingMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordManualMilestoneRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-49 — Backend-Owned Customer Action Required Projection (formal proof).
 *
 * <p><b>Scope result: no customer-action-required flag is exposed, by design.</b> This slice asked to
 * <em>add or formally prove</em> a backend-owned, customer-safe "action required" projection. After
 * tracing the trusted journey/milestone/signal domain, there is <em>no</em> backend-owned policy source
 * that determines a customer-facing action-required state:
 *
 * <ul>
 *   <li>The journey projection ({@link OrderJourney}) carries {@code currentStage}, {@code
 *       currentStatus}, {@code riskLevel}, {@code blocked}, {@code customerVisibleStatus}, {@code
 *       internalStatus}. None of these encodes "the customer must do something" — and {@code blocked}
 *       is explicitly modelled as <em>operator</em> attention required and is deliberately hidden from
 *       the customer-visible status ({@code OrderJourneyService.resolveCustomerVisibleStatus} skips
 *       {@code BLOCKED_EXCEPTION}: "never reveal internal block to customer").</li>
 *   <li>{@code FulfillmentSignalType.RETURN_REQUESTED} maps to <em>no</em> canonical milestone and has
 *       no customer-visible default; it is surfaced only as an <em>operator</em>-timeline warning flag
 *       ({@code OperatorFulfillmentTimelineResponse.returnRequested}). It does not model who must act,
 *       so it cannot be promoted to a customer "action required" without inventing business meaning.</li>
 *   <li>Payment milestones are always derived {@code UNKNOWN} (no payment mirror domain) — never a
 *       basis for a customer action.</li>
 * </ul>
 *
 * <p>OP-CAP-48 deliberately did not add a customer-action-required flag for exactly this reason (no
 * policy-approved action mechanism had been proven). Nothing in the domain has changed since, so the
 * honest, safe outcome is to <b>prove the absence</b> rather than invent fake action logic. These tests
 * pin that contract so a future change cannot quietly:
 *
 * <ol>
 *   <li>add a customer-action field to a customer-facing DTO without a real backend-owned policy source;</li>
 *   <li>let a customer/public request drive action state (the only client input remains an optional TTL);</li>
 *   <li>leak the operator-only {@code returnRequested}/{@code blocked} state — or any internal field —
 *       onto a customer surface; or</li>
 *   <li>change the operator timeline, which legitimately keeps that operator-only classification.</li>
 * </ol>
 *
 * <p>No production code is changed by this slice. When a real backend-owned customer-action policy
 * source exists (e.g. a modelled "awaiting customer" state with a determined responsible party), this
 * proof should be replaced by the implemented projection plus positive tests.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderJourneyService.class, OrderJourneyReadService.class, OrderJourneyTrackingLinkService.class,
    OrderJourneyProjectionPublisher.class, AuditEventService.class, CoreConfiguration.class})
class CustomerActionRequiredProjectionBoundaryStage49Test {
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyTrackingLinkService trackingLinkService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;

  private static final Instant NOW = Instant.parse("2026-06-26T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /**
   * Customer-facing DTOs (public buyer view + internal customer-safe view) must declare NO field that
   * asserts or labels a customer "action required" state. Any such field would imply a backend-owned
   * policy source that does not exist yet. {@code returnRequested}/{@code blocked} are included because
   * they are the closest trusted operator-only states and must never migrate onto a customer contract.
   */
  private static final Set<String> CUSTOMER_ACTION_FIELDS = Set.of(
      "customerActionRequired", "customerActionType", "customerActionLabel", "customerActionMessage",
      "customerAction", "actionRequired", "actionType", "actionLabel", "actionMessage", "actionSince",
      "requiresCustomerAction", "awaitingCustomer", "awaitingCustomerAction", "returnRequested", "blocked");

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private OrderJourney newApprovedQuoteJourney(UUID tenantId, String quoteNumber) {
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, quoteNumber, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    return journeyService.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
  }

  private static String token(TrackingLinkCreatedDto created) {
    return created.trackingPath().substring(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX.length());
  }

  private static Set<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  // ---- (3) the customer-safe contract exposes an action field ONLY when policy says it is safe ------
  //      No backend-owned policy source exists, so the contract exposes NO action field at all. -------

  @Test
  void noCustomerFacingDtoDeclaresACustomerActionField() {
    List<Class<?>> customerDtos = List.of(
        PublicOrderTrackingView.class, PublicTrackingMilestoneDto.class,
        CustomerSafeJourneyDto.class, CustomerTrackingMilestoneDto.class, CustomerTrackingEventDto.class);

    for (Class<?> dto : customerDtos) {
      assertThat(componentNames(dto))
          .as("%s must not expose any customer-action-required field until a backend-owned policy "
              + "source determines it", dto.getSimpleName())
          .doesNotContainAnyElementsOf(CUSTOMER_ACTION_FIELDS);
    }
  }

  // ---- (6) the operator timeline legitimately keeps the operator-only action-ish classification ------

  @Test
  void operatorTimelineStillCarriesTheTrustedReturnRequestedAndBlockedStateThatCustomersDoNot() {
    // The closest trusted "action-ish" states genuinely exist — they are derived server-side and live on
    // the OPERATOR contract only. This proves the separation is meaningful: the state exists, but it is
    // deliberately not a customer-facing action flag.
    assertThat(componentNames(OperatorFulfillmentTimelineResponse.class))
        .contains("returnRequested", "blocked");
  }

  // ---- (2) a customer/public request cannot drive action state (only an optional TTL is accepted) ----

  @Test
  void publicLinkRequestCannotSubmitOrOverrideAnyActionState() {
    // Minting a customer tracking link accepts ONLY an optional TTL. There is no action type/required/
    // message/since field a customer could submit, so a customer can neither set nor override action
    // state — it would be backend-owned if it existed at all.
    Set<String> requestFields = componentNames(CreateTrackingLinkRequest.class);
    assertThat(requestFields).containsExactly("expiresInHours");
    assertThat(requestFields).doesNotContainAnyElementsOf(CUSTOMER_ACTION_FIELDS);
  }

  // ---- (1)+(4)+(5) trusted operator-only return/blocked state never reaches a customer projection ----

  @Test
  void seededReturnAndBlockedStateNeverProduceACustomerActionOrLeakInternalsInPublicOrCustomerSafe()
      throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-ACTION");

    // Seed the two trusted states that an "action required" projection might be tempted to derive from:
    // an operator-internal RETURN_REQUESTED signal and a BLOCKED signal (journey becomes blocked). Both
    // carry unmistakable internal decoys that must never reach a customer surface.
    String returnSourceRefDecoy = "RET-IDEMP-KEY-SECRET-do-not-leak-049";
    String returnPayloadDecoy = "s3://internal-raw-bucket/return-rma-payload-SECRET-049";
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "REQUESTED", null, returnSourceRefDecoy, returnPayloadDecoy, false),
        UUID.randomUUID());

    String blockedSourceRefDecoy = "BLOCK-REF-SECRET-049";
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "BLOCKED", "BLOCKED", null, blockedSourceRefDecoy, null, false),
        UUID.randomUUID());

    // Operator-only manual milestone internal note — must never reach a customer surface.
    String internalNoteDecoy = "INTERNAL-customer-must-call-back-Acme-SECRET-049";
    journeyService.recordManualMilestone(journey.getId(), new RecordManualMilestoneRequest(
        "PACKED", internalNoteDecoy, "Your order has been packed", true), UUID.randomUUID());

    TrackingLinkCreatedDto created = trackingLinkService.create(
        journey.getId(), new CreateTrackingLinkRequest(49), null);

    // The journey IS blocked and HAS a return requested on the operator surface (trusted derived state).
    OperatorFulfillmentTimelineResponse operator = readService.operatorTimeline(journey.getId());
    assertThat(operator.returnRequested()).isTrue();
    assertThat(operator.blocked()).isTrue();

    // Public buyer view (scope from token only).
    TenantContext.clear();
    PublicOrderTrackingView publicView = trackingLinkService.resolvePublicTracking(token(created));
    String publicJson = JSON.writeValueAsString(publicView);

    // Internal permission-protected customer-safe view.
    TenantContext.setTenantId(tenantId);
    CustomerSafeJourneyDto customerSafe = readService.customerSafe(journey.getId());
    String customerSafeJson = JSON.writeValueAsString(customerSafe);

    for (String json : List.of(publicJson, customerSafeJson)) {
      String lower = json.toLowerCase(Locale.ROOT);
      // No customer-action flag of any spelling.
      assertThat(lower)
          .doesNotContain("customeraction")
          .doesNotContain("actionrequired")
          .doesNotContain("actiontype")
          .doesNotContain("actionlabel")
          .doesNotContain("actionmessage")
          .doesNotContain("awaitingcustomer")
          .doesNotContain("requirescustomeraction");
      // The deliberately-hidden internal block must not be revealed in any form.
      assertThat(lower).doesNotContain("blocked").doesNotContain("block-ref");
      // Operator-only return-requested state must not surface as a customer field/value.
      assertThat(json).doesNotContain("returnRequested").doesNotContain("RETURN_REQUESTED");
      // Seeded internal decoys (values).
      assertThat(json)
          .doesNotContain(returnSourceRefDecoy)
          .doesNotContain(returnPayloadDecoy)
          .doesNotContain(blockedSourceRefDecoy)
          .doesNotContain(internalNoteDecoy)
          .doesNotContain(tenantId.toString())
          .doesNotContain(journey.getSourceId().toString());
      // Internal/authority field names.
      assertThat(json)
          .doesNotContain("sourceRef")
          .doesNotContain("rawPayloadRef")
          .doesNotContain("idempotencyKey")
          .doesNotContain("riskLevel")
          .doesNotContain("internalStatus")
          .doesNotContain("\"customerVisible\"")
          .doesNotContain("tenantId")
          .doesNotContain("sortOrder");
    }

    // Positive proof the customer surfaces still carry their safe content (status + the safe packed note),
    // so this is a real redaction, not an empty response.
    assertThat(publicJson).contains("statusLabel").contains("milestones");
    assertThat(customerSafeJson).contains("customerVisibleStatus").contains("Your order has been packed");
  }

  // ---- (8) scope is backend-owned; a swapped tenant cannot reach another tenant's journey ------------

  @Test
  void swappedTenantScopeIsDeniedSoNoCustomerProjectionCrossesTenants() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-49-A");

    assertThatThrownBy(() -> readService.publicTracking(tenantB, journeyA.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- (9) an invalid / revoked-style unknown token resolves to a safe generic not-found -------------

  @Test
  void unknownTokenResolvesToGenericNotFoundWithNoValidityOracle() {
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking("not-a-real-token-049"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(""))
        .isInstanceOf(NotFoundException.class);
  }
}
