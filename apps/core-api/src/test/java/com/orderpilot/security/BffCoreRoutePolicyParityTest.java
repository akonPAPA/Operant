package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.security.ApiRouteSecurityPolicy.RouteDecision;
import com.orderpilot.security.ApiRouteSecurityPolicy.SecurityClassification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * F16 — machine-enforced BFF/Core route-permission parity.
 *
 * Reads the generated BFF tenant-route artifact (single source: the TypeScript registry) and runs the
 * REAL {@link ApiRouteSecurityPolicy#classify} against every registered route. Core remains the final
 * authority: for each browser-reachable route the BFF's declared permission must EXACTLY equal the
 * permission Core requires, the route must be a known protected tenant route (never public / support /
 * staff / internal), and read/mutation classification must agree. A weaker BFF permission, a wrong
 * method, a route Core does not know, or a forbidden plane all fail this test. No third hand-maintained
 * table exists: the BFF side is generated from registeredBffRoutes(), the Core side is the live policy.
 */
class BffCoreRoutePolicyParityTest {

  private static final String ARTIFACT_RELATIVE_PATH =
      "apps/web-dashboard/lib/bff/bff-tenant-routes.generated.json";
  private static final String SAMPLE_UUID = "11111111-1111-4111-8111-111111111111";

  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();

  private static JsonNode loadArtifact() throws IOException {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve(ARTIFACT_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return new ObjectMapper().readTree(Files.readString(candidate));
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Could not locate BFF route artifact " + ARTIFACT_RELATIVE_PATH);
  }

  /** Turn a BFF slash-joined template ("api/v1/x/:fooId") into a concrete Core path. */
  private static String concretePath(String pattern) {
    StringBuilder out = new StringBuilder();
    for (String segment : pattern.split("/")) {
      out.append('/');
      if (segment.startsWith(":")) {
        String name = segment.substring(1);
        out.append(name.endsWith("Id") ? SAMPLE_UUID : "sample");
      } else {
        out.append(segment);
      }
    }
    return out.toString();
  }

  @Test
  void everyBffRouteMatchesCorePermissionExactlyAndStaysOnTheTenantPlane() throws IOException {
    JsonNode artifact = loadArtifact();
    JsonNode routes = artifact.get("routes");
    assertThat(routes).isNotNull();
    assertThat(routes.size()).isGreaterThan(100);

    for (JsonNode route : routes) {
      String method = route.get("method").asText();
      String pattern = route.get("pattern").asText();
      String bffPermission = route.get("permission").asText();
      String kind = route.get("kind").asText();
      String corePath = concretePath(pattern);
      String label = method + " " + corePath + " (BFF " + bffPermission + ")";

      Optional<RouteDecision> decision = policy.classify(method, corePath);
      assertThat(decision).as("Core must know browser-reachable route: " + label).isPresent();
      RouteDecision d = decision.get();

      // Never a public / webhook / secure-link plane, and never a support/staff/internal permission.
      assertThat(d.isPublic()).as("route must not be public: " + label).isFalse();
      String corePermission = d.requiredPermission().name();
      assertThat(corePermission)
          .as("no support/staff plane may be browser-reachable: " + label)
          .doesNotStartWith("STAFF_");

      // Exact permission parity — the BFF must never be weaker than or different from Core.
      assertThat(bffPermission)
          .as("BFF permission must equal the Core required permission: " + label)
          .isEqualTo(corePermission);

      // Read/mutation classification must agree.
      if ("read".equals(kind)) {
        assertThat(d.classification())
            .as("read route must classify as PROTECTED_READ: " + label)
            .isEqualTo(SecurityClassification.PROTECTED_READ);
      } else {
        assertThat(d.classification())
            .as("mutation route must not classify as PROTECTED_READ: " + label)
            .isNotEqualTo(SecurityClassification.PROTECTED_READ);
      }
    }
  }
}
