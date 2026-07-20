package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventProjection;
import com.orderpilot.application.services.control.OperationalEventReadService.InvalidOperationalEventQueryException;
import org.junit.jupiter.api.Test;

/**
 * P1-E lifecycle (operational-event slice) proof that the read is bounded, newest-first, correctly
 * cursor-paginated, filterable by the closed allowlists, and fails closed on malformed input. The
 * projection only ever carries the typed allowlisted fields.
 */
class OperationalEventReadServiceTest {

  private static OperationalEventReadService serviceWith(OperationalEventBuffer buffer) {
    return new OperationalEventReadService(buffer);
  }

  private static void append(
      OperationalEventBuffer buffer, OperationalEventCode code, OperationalEventComponent component,
      OperationalEventSeverity severity) {
    buffer.append(1_000L, code, component, severity, "summary", null);
  }

  @Test
  void emptyBufferReturnsEmptyBoundedPageWithHonestScope() {
    OperationalEventPage page = serviceWith(new OperationalEventBuffer()).read(null, null, null, null, null);
    assertThat(page.events()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.returned()).isZero();
    assertThat(page.maxLimit()).isEqualTo(OperationalEventReadService.MAX_LIMIT);
    assertThat(page.scope()).isEqualTo("LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS");
    assertThat(page.instanceId()).isNotBlank();
  }

  @Test
  void returnsProjectionsNewestFirstWithOnlyTypedFields() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    append(buffer, OperationalEventCode.DEPENDENCY_STATE_CHANGED, OperationalEventComponent.DATABASE,
        OperationalEventSeverity.INFO);
    append(buffer, OperationalEventCode.READINESS_STATE_CHANGED, OperationalEventComponent.PLATFORM,
        OperationalEventSeverity.WARN);

    OperationalEventPage page = serviceWith(buffer).read(null, null, null, null, null);
    assertThat(page.events()).hasSize(2);
    OperationalEventProjection newest = page.events().get(0);
    assertThat(newest.eventCode()).isEqualTo("READINESS_STATE_CHANGED");
    assertThat(newest.component()).isEqualTo("PLATFORM");
    assertThat(newest.severity()).isEqualTo("WARN");
    assertThat(newest.occurredAt()).endsWith("Z");
  }

  @Test
  void clampsLimitAndDefaults() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    for (int i = 0; i < 5; i++) {
      append(buffer, OperationalEventCode.DEPENDENCY_STATE_CHANGED, OperationalEventComponent.DATABASE,
          OperationalEventSeverity.INFO);
    }
    assertThat(serviceWith(buffer).read(null, null, null, "9999", null).events()).hasSize(5);
    assertThat(serviceWith(buffer).read(null, null, null, null, null).events()).hasSize(5);
  }

  @Test
  void paginatesWithBeforeCursor() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    for (int i = 0; i < 5; i++) {
      append(buffer, OperationalEventCode.DEPENDENCY_STATE_CHANGED, OperationalEventComponent.DATABASE,
          OperationalEventSeverity.INFO);
    }
    OperationalEventReadService service = serviceWith(buffer);

    OperationalEventPage first = service.read(null, null, null, "2", null);
    assertThat(first.events()).hasSize(2);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.nextCursor()).isEqualTo("4");

    OperationalEventPage second = service.read(null, null, null, "2", first.nextCursor());
    assertThat(second.events()).hasSize(2);
    assertThat(second.hasMore()).isTrue();

    OperationalEventPage last = service.read(null, null, null, "2", second.nextCursor());
    assertThat(last.events()).hasSize(1);
    assertThat(last.hasMore()).isFalse();
    assertThat(last.nextCursor()).isNull();
  }

  @Test
  void filtersBySeverityComponentAndEventCode() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    append(buffer, OperationalEventCode.DEPENDENCY_STATE_CHANGED, OperationalEventComponent.DATABASE,
        OperationalEventSeverity.ERROR);
    append(buffer, OperationalEventCode.DEPENDENCY_STATE_CHANGED, OperationalEventComponent.REDIS,
        OperationalEventSeverity.INFO);
    append(buffer, OperationalEventCode.READINESS_STATE_CHANGED, OperationalEventComponent.PLATFORM,
        OperationalEventSeverity.WARN);
    OperationalEventReadService service = serviceWith(buffer);

    assertThat(service.read("error", null, null, null, null).events())
        .extracting(OperationalEventProjection::severity).containsExactly("ERROR");
    assertThat(service.read(null, "redis", null, null, null).events())
        .extracting(OperationalEventProjection::component).containsExactly("REDIS");
    assertThat(service.read(null, null, "readiness_state_changed", null, null).events())
        .extracting(OperationalEventProjection::eventCode).containsExactly("READINESS_STATE_CHANGED");
  }

  @Test
  void failsClosedOnUnknownAllowlistValues() {
    OperationalEventReadService service = serviceWith(new OperationalEventBuffer());
    assertThatThrownBy(() -> service.read("CRITICAL", null, null, null, null))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
    assertThatThrownBy(() -> service.read(null, "kafka", null, null, null))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
    assertThatThrownBy(() -> service.read(null, null, "BACKUP_STARTED", null, null))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
  }

  @Test
  void failsClosedOnMalformedLimitAndBefore() {
    OperationalEventReadService service = serviceWith(new OperationalEventBuffer());
    assertThatThrownBy(() -> service.read(null, null, null, "0", null))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
    assertThatThrownBy(() -> service.read(null, null, null, "abc", null))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
    assertThatThrownBy(() -> service.read(null, null, null, null, "-1"))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
    assertThatThrownBy(() -> service.read(null, null, null, null, "99999999999999999999"))
        .isInstanceOf(InvalidOperationalEventQueryException.class);
  }
}
