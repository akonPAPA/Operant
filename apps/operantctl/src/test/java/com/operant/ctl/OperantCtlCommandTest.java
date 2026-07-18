package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Command behavior against a local fake control API: control credential headers are actually sent,
 * the command-to-path map is a fixed allowlist, exit codes are structured, denial and transport
 * failures map to their own codes, and no secret material reaches stdout/stderr.
 */
class OperantCtlCommandTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String ALIAS = "ops-prod";

  private HttpServer server;
  private Map<String, String> env;
  private InMemoryControlCredentialStore credentialStore;
  private final Map<String, Map<String, String>> capturedHeadersByPath = new ConcurrentHashMap<>();
  private final Map<String, String> responseByPath = new ConcurrentHashMap<>();
  private int responseStatus = 200;

  @BeforeEach
  void startFakeControlApi() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      Map<String, String> headers = new HashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
      capturedHeadersByPath.put(exchange.getRequestURI().getPath(), headers);
      byte[] body = responseByPath
          .getOrDefault(exchange.getRequestURI().getPath(), "{}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(responseStatus, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    credentialStore = new InMemoryControlCredentialStore();
    credentialStore.store(ALIAS, new ControlCredential(SECRET));
    env = new HashMap<>();
    env.put(CtlConfig.ENV_CORE_BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort());
    env.put(CtlConfig.ENV_CREDENTIAL_ALIAS, ALIAS);
    env.put(CtlConfig.ENV_MODE, "local");
  }

  @AfterEach
  void stopFakeControlApi() {
    server.stop(0);
  }

  private record RunResult(int exitCode, String out, String err) {}

  private RunResult run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = OperantCtl.run(
        args,
        env,
        credentialStore,
        Clock.systemUTC(),
        new PrintStream(out, true),
        new PrintStream(err, true));
    return new RunResult(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void versionIsLocalAndStructured() {
    RunResult result = run("version");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("operantctl 0.1.0").contains("protocol v1");
    assertThat(capturedHeadersByPath).isEmpty();
  }

  @Test
  void configValidateReportsProblemsWithStructuredExit() {
    assertThat(run("config", "validate").exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    env.put(CtlConfig.ENV_CORE_BASE_URL, "http://operant.example");
    RunResult invalid = run("config", "validate");
    assertThat(invalid.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(invalid.err()).contains(CtlConfig.ENV_CORE_BASE_URL);
  }

  @Test
  void statusSendsVerifiableControlCredentialRequestWithoutTenantActorOrPermissions() {
    responseByPath.put(ControlApiClient.STATUS_PATH, "{\"version\":\"unknown\"}");
    RunResult result = run("status");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("\"version\":\"unknown\"");

    Map<String, String> headers = capturedHeadersByPath.get(ControlApiClient.STATUS_PATH);
    assertThat(headers).isNotNull();
    assertThat(headers.get("X-orderpilot-control-credential")).isEqualTo(ALIAS);
    assertThat(headers.get("X-orderpilot-control-audience")).isEqualTo(ControlPlaneSigner.AUDIENCE);
    assertThat(headers.get("X-orderpilot-control-signature-version")).isEqualTo("1");
    assertThat(headers).doesNotContainKeys(
        "X-tenant-id",
        "X-orderpilot-actor-id",
        "X-orderpilot-permissions",
        "X-orderpilot-gateway-key");

    ControlPlaneSigner signer = new ControlPlaneSigner(new ControlCredential(SECRET).keyMaterialCopy(), ALIAS);
    String canonical = ControlPlaneSigner.canonical(
        "GET",
        ControlApiClient.STATUS_PATH,
        "",
        "",
        headers.get("X-orderpilot-control-content-sha256"),
        headers.get("X-orderpilot-control-audience"),
        ALIAS,
        Long.parseLong(headers.get("X-orderpilot-control-timestamp")),
        headers.get("X-orderpilot-control-nonce"));
    assertThat(headers.get("X-orderpilot-control-signature")).isEqualTo(signer.hmacHex(canonical));
  }

  @Test
  void diagnoseChoosesOnlyTheFixedDiagnosticsPath() {
    responseByPath.put(ControlApiClient.DIAGNOSTICS_PATH, "{\"version\":\"unknown\"}");
    RunResult result = run("diagnose");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(capturedHeadersByPath).containsKey(ControlApiClient.DIAGNOSTICS_PATH);
    assertThat(capturedHeadersByPath.get(ControlApiClient.DIAGNOSTICS_PATH))
        .doesNotContainKey("X-orderpilot-permissions");
  }

  @Test
  void readinessMapsReadyFlagToExitCode() {
    responseByPath.put(ControlApiClient.READINESS_PATH, "{\"ready\":true,\"dependencies\":[]}");
    assertThat(run("readiness").exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    responseByPath.put(ControlApiClient.READINESS_PATH, "{\"ready\":false,\"dependencies\":[]}");
    assertThat(run("readiness").exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
  }

  @Test
  void deniedRequestsExitWithDeniedCode() {
    responseStatus = 403;
    RunResult result = run("status");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_DENIED);
    assertThat(result.err()).contains("denied");
  }

  @Test
  void transportFailureExitsWithTransportCodeWithoutSecretLeak() {
    server.stop(0);
    RunResult result = run("status");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_TRANSPORT);
    assertThat(result.out() + result.err()).doesNotContain(SECRET);
  }

  @Test
  void unknownAndOversuppliedCommandsAreUsageErrors() {
    assertThat(run("shell").exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(run("status", "--path=/etc/passwd").exitCode())
        .isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(run().exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(capturedHeadersByPath).isEmpty();
  }

  @Test
  void noCommandEverLeaksTheSecretToOutput() {
    responseByPath.put(ControlApiClient.STATUS_PATH, "{\"version\":\"unknown\"}");
    for (List<String> command : List.of(
        List.of("version"), List.of("config", "validate"), List.of("status"))) {
      RunResult result = run(command.toArray(String[]::new));
      assertThat(result.out() + result.err()).doesNotContain(SECRET);
    }
  }
}