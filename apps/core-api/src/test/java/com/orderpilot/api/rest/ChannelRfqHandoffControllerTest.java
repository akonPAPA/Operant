package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * OP-CAP-06C contract guard: the RFQ handoff API exposes operator read + workflow-transition
 * endpoints only. It must NOT expose a public/manual create endpoint — handoffs are created solely
 * by the verified channel/bot bridge flow. This is a fast reflection check (no Spring context).
 */
class ChannelRfqHandoffControllerTest {

  @Test void exposesOnlyReadAndTransitionEndpointsAndNoManualCreate() {
    List<String> postPaths = new ArrayList<>();
    List<String> getPaths = new ArrayList<>();
    for (Method method : ChannelRfqHandoffController.class.getDeclaredMethods()) {
      PostMapping post = method.getAnnotation(PostMapping.class);
      if (post != null) {
        postPaths.addAll(Arrays.asList(post.value()));
      }
      GetMapping get = method.getAnnotation(GetMapping.class);
      if (get != null) {
        getPaths.addAll(Arrays.asList(get.value()));
      }
    }

    // Reads: list + get-by-id.
    assertThat(getPaths).containsExactlyInAnyOrder(
        "/api/v1/channels/rfq-handoffs",
        "/api/v1/channels/rfq-handoffs/{id}");

    // Writes: only the three operator transitions, each scoped to a specific handoff id.
    assertThat(postPaths).containsExactlyInAnyOrder(
        "/api/v1/channels/rfq-handoffs/{id}/start-review",
        "/api/v1/channels/rfq-handoffs/{id}/dismiss",
        "/api/v1/channels/rfq-handoffs/{id}/mark-converted");

    // No manual/public create endpoint: every POST targets an existing {id}, none creates a handoff.
    assertThat(postPaths).noneMatch(path -> path.equals("/api/v1/channels/rfq-handoffs"));
    assertThat(postPaths).allMatch(path -> path.contains("/{id}/"));
  }
}
