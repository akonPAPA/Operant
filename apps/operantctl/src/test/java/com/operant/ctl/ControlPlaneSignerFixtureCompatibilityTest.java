package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Control credential signer proof: no tenant, actor, or permission authority is in the contract. */
class ControlPlaneSignerFixtureCompatibilityTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String ALIAS = "ops-prod";
  private static final String PATH = "/api/v1/internal/control/status";

  @Test
  void signerBuildsControlCredentialHeadersWithoutTenantActorOrPermissionAuthority() {
    ControlPlaneSigner signer = new ControlPlaneSigner(new ControlCredential(SECRET).keyMaterialCopy(), ALIAS);
    Map<String, String> headers = signer.signedGetHeaders(PATH, 1_800_000_000L);

    assertThat(headers).containsEntry(ControlPlaneSigner.CREDENTIAL_HEADER, ALIAS);
    assertThat(headers).containsEntry(ControlPlaneSigner.AUDIENCE_HEADER, ControlPlaneSigner.AUDIENCE);
    assertThat(headers).containsEntry(ControlPlaneSigner.VERSION_HEADER, ControlPlaneSigner.SIGNATURE_VERSION);
    assertThat(headers).containsEntry(ControlPlaneSigner.CONTENT_SHA256_HEADER, ControlPlaneSigner.EMPTY_BODY_SHA256_HEX);
    assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Tenant-Id"));
    assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("X-OrderPilot-Actor-Id"));
    assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("X-OrderPilot-Permissions"));
  }

  @Test
  void canonicalStringContainsOnlyControlCredentialFacts() {
    String canonical = ControlPlaneSigner.canonical(
        "GET",
        PATH,
        "",
        "",
        ControlPlaneSigner.EMPTY_BODY_SHA256_HEX,
        ControlPlaneSigner.AUDIENCE,
        ALIAS,
        1_800_000_000L,
        "nonce-1");

    assertThat(canonical).isEqualTo(String.join("\n",
        "OPERANT_CONTROL_V1",
        "GET",
        PATH,
        "",
        "",
        ControlPlaneSigner.EMPTY_BODY_SHA256_HEX,
        "orderpilot-control-plane",
        ALIAS,
        "1800000000",
        "nonce-1"));
    assertThat(canonical).doesNotContain("STAFF_CONTROL_READ");
    assertThat(canonical).doesNotContain("11111111-1111-4111-8111-111111111111");
  }
}