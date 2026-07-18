package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedGatewayHeaderStripArtifactTest {
  private static final Path REPO_ROOT = Path.of("../..").normalize();
  private static final Path ARTIFACT =
      REPO_ROOT.resolve("docs/security/gateway-header-strip-nginx-example.conf");
  private static final Path BOUNDARY_DOC =
      REPO_ROOT.resolve("docs/security/TRUSTED_GATEWAY_HEADER_BOUNDARY.md");

  private static final List<String> TRUSTED_HEADERS = List.of(
      "X-Tenant-Id",
      "X-OrderPilot-Tenant-Id",
      "X-OrderPilot-Actor-Id",
      "X-OrderPilot-Permissions",
      "X-OrderPilot-Gateway-Timestamp",
      "X-OrderPilot-Gateway-Nonce",
      "X-OrderPilot-Gateway-Signature",
      "X-OrderPilot-Actor-Signature",
      "X-OrderPilot-Actor-Timestamp",
      "X-OrderPilot-Gateway-Key");

  @Test
  void gatewayHeaderStripArtifactMentionsAndClearsTrustedAuthorityHeaders() throws Exception {
    String artifact = Files.readString(ARTIFACT);

    assertThat(artifact)
        .contains("proxy_pass_request_headers off")
        .contains("auth_request")
        .contains("proxy_pass http://orderpilot_core_api_private")
        .contains("Do not expose core-api directly");

    for (String header : TRUSTED_HEADERS) {
      assertThat(artifact).contains(header);
    }

    assertThat(artifact)
        .contains("proxy_set_header X-Tenant-Id \"\";")
        .contains("proxy_set_header X-OrderPilot-Actor-Id \"\";")
        .contains("proxy_set_header X-OrderPilot-Permissions \"\";")
        .contains("proxy_set_header X-OrderPilot-Gateway-Signature \"\";")
        .contains("proxy_set_header X-OrderPilot-Gateway-Nonce \"\";");
  }

  @Test
  void gatewayHeaderStripArtifactInjectsOnlyGatewayOwnedSignedHeaders() throws Exception {
    String artifact = Files.readString(ARTIFACT);

    assertThat(artifact)
        .contains("auth_request_set $op_tenant_id")
        .contains("auth_request_set $op_actor_id")
        .contains("auth_request_set $op_permissions")
        .contains("auth_request_set $op_timestamp")
        .contains("auth_request_set $op_nonce")
        .contains("auth_request_set $op_signature")
        .contains("proxy_set_header X-Tenant-Id                       $op_tenant_id;")
        .contains("proxy_set_header X-OrderPilot-Actor-Id             $op_actor_id;")
        .contains("proxy_set_header X-OrderPilot-Permissions          $op_permissions;")
        .contains("proxy_set_header X-OrderPilot-Gateway-Timestamp    $op_timestamp;")
        .contains("proxy_set_header X-OrderPilot-Gateway-Nonce        $op_nonce;")
        .contains("proxy_set_header X-OrderPilot-Gateway-Signature    $op_signature;");
  }

  @Test
  void trustedGatewayDocsCaptureProductionTopologyAndRedisReplayRequirement() throws Exception {
    String doc = Files.readString(BOUNDARY_DOC);

    assertThat(doc)
        .contains("Threat model")
        .contains("Required production topology")
        .contains("Deployment review checklist")
        .contains("directly from the public internet")
        .contains("replay-store=redis")
        .contains("gateway-header-strip-nginx-example.conf");
  }

  @Test
  void gatewayHeaderStripArtifactContainsNoRealSecretMaterial() throws Exception {
    String artifact = Files.readString(ARTIFACT);

    assertThat(artifact)
        .doesNotContain("BEGIN PRIVATE KEY")
        .doesNotContain("BEGIN CERTIFICATE")
        .doesNotContain("ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET=")
        .doesNotContain("a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517")
        .doesNotContain("change-me")
        .doesNotContain("password");
  }
}
