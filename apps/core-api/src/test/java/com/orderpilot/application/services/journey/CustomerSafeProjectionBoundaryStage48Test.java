package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingEventDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorFulfillmentTimelineResponse;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorTimelineEntry;
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
 * OP-CAP-48 — Customer-Safe Tracking Projection Boundary.
 *
 * <p>This slice hardens the already-implemented boundary (OP-CAP-46B/C/E/G/H, OP-CAP-47A/B/C) with
 * explicit, consolidated projection and leak proofs. It asserts the contract that:
 * <ol>
 *   <li>the operator fulfillment timeline contract and the customer-facing contracts are
 *       <em>separate types</em> (the operator DTO is never reused for a customer surface);</li>
 *   <li>the customer-facing DTOs structurally carry <em>no</em> internal/authority field (no tenant,
 *       actor, source, raw payload, idempotency, confidence, risk, internal status, sort order, or
 *       customer-visible flag) — the public buyer view additionally carries no identifier at all;</li>
 *   <li>internal "decoy" signal/milestone material seeded across <em>every</em> internal field never
 *       appears in the serialized public OR internal customer-safe projection;</li>
 *   <li>scope is backend-owned: a customer/public request cannot drive tenant/journey/status/stage/
 *       risk/return/approval/execution — the only client input to a link is an optional TTL, and a
 *       swapped (tenant, journey) scope is denied; and</li>
 *   <li>the operator timeline behaviour is unchanged (the operator DTO still exposes its operator-safe
 *       signal classification, which the customer contracts deliberately do not).</li>
 * </ol>
 *
 * <p>No production code is changed by this slice — the boundary already holds; these tests pin it.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderJourneyService.class, OrderJourneyReadService.class, OrderJourneyTrackingLinkService.class,
    OrderJourneyProjectionPublisher.class, AuditEventService.class, CoreConfiguration.class})
class CustomerSafeProjectionBoundaryStage48Test {
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyTrackingLinkService trackingLinkService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;

  private static final Instant NOW = Instant.parse("2026-06-26T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /**
   * Internal/authority field names that must NEVER appear on ANY customer-facing DTO (public buyer
   * view or internal permission-protected customer-safe view). Mirrors the operator-only and internal
   * material enumerated in the OP-CAP-48 hard constraints.
   */
  private static final Set<String> INTERNAL_FORBIDDEN_FIELDS = Set.of(
      "tenantId", "actorId", "actorType", "sourceId", "sourceType", "sourceRef", "rawPayloadRef",
      "rawPayload", "payloadRef", "storageRef", "idempotencyKey", "idempotencyKeyHash", "confidence",
      "riskLevel", "internalStatus", "currentStage", "currentStatus", "blocked", "sortOrder",
      "customerVisible", "auditEventIds", "auditEventId", "tokenHash", "signalId", "signalCount",
      "sequence", "returnRequested", "internalNote", "evidenceSource", "processingMetadata");

  /** The public buyer view is additionally identifier-free (scope is proven by the token). */
  private static final Set<String> PUBLIC_VIEW_FORBIDDEN_IDENTIFIERS = Set.of("id", "journeyId");

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

  // ---- (1) + (2) operator vs customer are SEPARATE contracts, customer carries no internal field ----

  @Test
  void operatorTimelineContractIsADistinctTypeFromCustomerContracts() {
    // The operator timeline DTO is its own type — never structurally substitutable for a customer view.
    assertThat(OperatorFulfillmentTimelineResponse.class)
        .isNotEqualTo(PublicOrderTrackingView.class)
        .isNotEqualTo(CustomerSafeJourneyDto.class);
    assertThat(OperatorTimelineEntry.class)
        .isNotEqualTo(PublicTrackingMilestoneDto.class)
        .isNotEqualTo(CustomerTrackingMilestoneDto.class);

    // And the operator DTO genuinely carries operator-only classification that the customer DTOs omit,
    // so the separation is meaningful (not two names for the same shape).
    assertThat(componentNames(OperatorTimelineEntry.class))
        .contains("sequence", "sourceType", "status", "customerVisible");
    assertThat(componentNames(OperatorFulfillmentTimelineResponse.class))
        .contains("currentStage", "currentStatus", "riskLevel", "blocked", "signalCount", "returnRequested");

    // None of the operator-ONLY fields exist on the customer milestone contracts. (Genuinely safe,
    // deliberately shared classification such as evidenceLevel / milestone state is allowed on both.)
    Set<String> operatorOnlyEntryFields = Set.of(
        "sequence", "type", "status", "sourceType", "customerVisible", "receivedAt", "processedAt");
    assertThat(componentNames(PublicTrackingMilestoneDto.class)).doesNotContainAnyElementsOf(operatorOnlyEntryFields);
    assertThat(componentNames(CustomerTrackingMilestoneDto.class)).doesNotContainAnyElementsOf(operatorOnlyEntryFields);
  }

  @Test
  void customerFacingDtosDeclareNoInternalOrAuthorityFields() {
    List<Class<?>> customerDtos = List.of(
        PublicOrderTrackingView.class, PublicTrackingMilestoneDto.class,
        CustomerSafeJourneyDto.class, CustomerTrackingMilestoneDto.class, CustomerTrackingEventDto.class);

    for (Class<?> dto : customerDtos) {
      Set<String> fields = componentNames(dto);
      assertThat(fields)
          .as("%s must declare no internal/authority field", dto.getSimpleName())
          .doesNotContainAnyElementsOf(INTERNAL_FORBIDDEN_FIELDS);
    }

    // The PUBLIC buyer view (and its milestone) carry no identifier at all — scope is proven by the
    // token, so nothing needs to be echoed. (The internal, permission-protected CustomerSafeJourneyDto
    // legitimately echoes the journey resource id already present in its own URL.)
    assertThat(componentNames(PublicOrderTrackingView.class))
        .doesNotContainAnyElementsOf(PUBLIC_VIEW_FORBIDDEN_IDENTIFIERS);
    assertThat(componentNames(PublicTrackingMilestoneDto.class))
        .doesNotContainAnyElementsOf(PUBLIC_VIEW_FORBIDDEN_IDENTIFIERS);
  }

  // ---- (3) seeded internal decoys never reach either customer-facing projection ------------------

  @Test
  void seededInternalDecoysNeverAppearInPublicOrCustomerSafeProjection() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-DECOY");

    // Seed unmistakable decoys into EVERY internal signal field that an operator surface may carry.
    String sourceRefDecoy = "EXT-IDEMP-KEY-SECRET-do-not-leak-001";
    String rawPayloadDecoy = "s3://internal-raw-bucket/carrier-payload-SECRET-9f";
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "CONNECTOR_MIRROR", "SHIPPED", "SHIPPED", null, sourceRefDecoy, rawPayloadDecoy, true),
        UUID.randomUUID());

    // Seed an operator-only manual milestone note (must never reach a customer surface) plus a safe
    // customer note (which legitimately may surface on the internal customer-safe event).
    String internalNoteDecoy = "INTERNAL-supplier-margin-23pct-Acme-SECRET";
    journeyService.recordManualMilestone(journey.getId(), new RecordManualMilestoneRequest(
        "PACKED", internalNoteDecoy, "Your order has been packed", true), UUID.randomUUID());

    TrackingLinkCreatedDto created = trackingLinkService.create(
        journey.getId(), new CreateTrackingLinkRequest(48), null);

    // Public buyer view (scope from token only).
    TenantContext.clear();
    PublicOrderTrackingView publicView = trackingLinkService.resolvePublicTracking(token(created));
    String publicJson = JSON.writeValueAsString(publicView);

    // Internal permission-protected customer-safe view.
    TenantContext.setTenantId(tenantId);
    CustomerSafeJourneyDto customerSafe = readService.customerSafe(journey.getId());
    String customerSafeJson = JSON.writeValueAsString(customerSafe);

    for (String json : List.of(publicJson, customerSafeJson)) {
      assertThat(json)
          // seeded internal values
          .doesNotContain(sourceRefDecoy)
          .doesNotContain(rawPayloadDecoy)
          .doesNotContain(internalNoteDecoy)
          .doesNotContain(tenantId.toString())
          .doesNotContain(journey.getSourceId().toString())
          // internal/authority field names
          .doesNotContain("sourceRef")
          .doesNotContain("rawPayloadRef")
          .doesNotContain("idempotencyKey")
          .doesNotContain("confidence")
          .doesNotContain("riskLevel")
          .doesNotContain("internalStatus")
          .doesNotContain("sortOrder")
          // exact JSON key — the safe customer status field is "customerVisibleStatus", which must not
          // be mistaken for the internal per-milestone/event "customerVisible" boolean.
          .doesNotContain("\"customerVisible\"")
          .doesNotContain("connector")
          .doesNotContain("tenantId")
          .doesNotContain("signalCount")
          .doesNotContain("sequence")
          .doesNotContain("returnRequested");
    }

    // Positive proof the customer surfaces still carry their safe content.
    assertThat(publicJson).contains("statusLabel").contains("milestones");
    assertThat(customerSafeJson).contains("customerVisibleStatus").contains("Your order has been packed");
  }

  // ---- (4) scope is backend-owned; the customer/public request cannot drive authority/state -------

  @Test
  void publicLinkRequestCarriesOnlyTtlAndNeverAnAuthorityOrStateField() {
    // The ONLY client-supplied input to minting a customer link is an optional TTL. Status, stage,
    // risk, milestone, tenant, actor, source, approval, execution and return state are all absent —
    // they are backend-owned and can never be set by the request body.
    assertThat(componentNames(CreateTrackingLinkRequest.class)).containsExactly("expiresInHours");
  }

  @Test
  void swappedScopeIsDeniedSoAtokenCannotReachAnotherTenantsJourney() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-SCOPE-A");

    // The customer-safe projection is bound to the (tenant, journey) the backend resolves — a swapped
    // tenant for the same journey id is denied with a generic not-found (no cross-tenant read).
    assertThatThrownBy(() -> readService.publicTracking(tenantB, journeyA.getId()))
        .isInstanceOf(NotFoundException.class);
  }
}
