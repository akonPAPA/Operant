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
  private static final String VALID_STATUS =
      "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"NOT_CONFIGURED\"}]}";
  private static final String VALID_HEALTH = "{\"status\":\"UP\"}";
  private static final String VALID_READY =
      "{\"ready\":true,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"}]}";
  private static final String VALID_DIAGNOSTICS =
      "{\"version\":\"unknown\",\"activeProfiles\":[\"production\"],\"database\":{\"state\":\"UP\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":true,\"state\":\"UP\"},\"jvm\":{\"heapUsedMb\":1,\"heapMaxMb\":256}}";

  private HttpServer server;
  private Map<String, String> env;
  private InMemoryControlCredentialStore credentialStore;
  private final Map<String, Map<String, String>> capturedHeadersByPath = new ConcurrentHashMap<>();
  private final Map<String, String> responseByPath = new ConcurrentHashMap<>();
  private final Map<String, byte[]> responseBytesByPath = new ConcurrentHashMap<>();
  private int responseStatus = 200;

  @BeforeEach
  void startFakeControlApi() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      Map<String, String> headers = new HashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
      capturedHeadersByPath.put(exchange.getRequestURI().getPath(), headers);
      byte[] body = responseBytesByPath.get(exchange.getRequestURI().getPath());
      if (body == null) {
        body = responseByPath
            .getOrDefault(exchange.getRequestURI().getPath(), "{}")
            .getBytes(StandardCharsets.UTF_8);
      }
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
    responseByPath.put(ControlApiClient.STATUS_PATH, VALID_STATUS);
    responseByPath.put(ControlApiClient.HEALTH_PATH, VALID_HEALTH);
    responseByPath.put(ControlApiClient.READINESS_PATH, VALID_READY);
    responseByPath.put(ControlApiClient.DIAGNOSTICS_PATH, VALID_DIAGNOSTICS);
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
    RunResult result = run("status");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("\"version\":\"unknown\"").contains("\"dependencies\"");

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
    RunResult result = run("diagnose");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(capturedHeadersByPath).containsKey(ControlApiClient.DIAGNOSTICS_PATH);
    assertThat(capturedHeadersByPath.get(ControlApiClient.DIAGNOSTICS_PATH))
        .doesNotContainKey("X-orderpilot-permissions");
  }

  @Test
  void readinessMapsReadyFlagToExitCode() {
    responseByPath.put(ControlApiClient.READINESS_PATH,
        "{\"ready\":true,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"}]}");
    assertThat(run("readiness").exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    responseByPath.put(ControlApiClient.READINESS_PATH,
        "{\"ready\":false,\"dependencies\":[{\"name\":\"database\",\"state\":\"DOWN\"}]}");
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
  void successfulCommandsRejectInvalidHttp200BodiesBeforePrinting() {
    for (String invalid : List.of(
        "{",
        "{}",
        "{\"version\":\"unknown\"}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":-1,\"dependencies\":[]}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":123456789012345678901234567890123,\"dependencies\":[]}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[{\"name\":\"database\",\"state\":\"MAYBE\"}]}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[],\"controlSecret\":\"do-not-print\"}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[]} trailing")) {
      responseByPath.put(ControlApiClient.STATUS_PATH, invalid);
      RunResult result = run("status");
      assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
      assertThat(result.out()).isBlank();
      assertThat(result.err()).contains("response failed validation").doesNotContain("do-not-print");
    }
  }

  @Test
  void duplicateFieldsMalformedUtf8AndExcessiveNestingAreRejectedBeforePrinting() {
    responseByPath.put(ControlApiClient.STATUS_PATH,
        "{\"version\":\"unknown\",\"version\":\"other\",\"uptimeSeconds\":1,\"dependencies\":[]}");
    RunResult duplicate = run("status");
    assertThat(duplicate.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(duplicate.out()).isBlank();
    assertThat(duplicate.err()).contains("response failed validation");

    responseByPath.put(ControlApiClient.DIAGNOSTICS_PATH,
        "{\"version\":\"unknown\",\"activeProfiles\":[\"production\"],\"database\":{\"state\":\"UP\",\"state\":\"DOWN\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":true,\"state\":\"UP\"},\"jvm\":{\"heapUsedMb\":1,\"heapMaxMb\":256}}");
    RunResult nestedDuplicate = run("diagnose");
    assertThat(nestedDuplicate.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(nestedDuplicate.out()).isBlank();
    assertThat(nestedDuplicate.err()).contains("response failed validation");

    byte[] prefix = "{\"version\":\"".getBytes(StandardCharsets.UTF_8);
    byte[] invalid = new byte[] {(byte) 0xc3, (byte) 0x28};
    byte[] suffix = "\",\"uptimeSeconds\":1,\"dependencies\":[]}".getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[prefix.length + invalid.length + suffix.length];
    System.arraycopy(prefix, 0, body, 0, prefix.length);
    System.arraycopy(invalid, 0, body, prefix.length, invalid.length);
    System.arraycopy(suffix, 0, body, prefix.length + invalid.length, suffix.length);
    responseBytesByPath.put(ControlApiClient.STATUS_PATH, body);
    RunResult malformedUtf8 = run("status");
    assertThat(malformedUtf8.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(malformedUtf8.out()).isBlank();
    assertThat(malformedUtf8.err()).contains("response failed validation");
    responseBytesByPath.remove(ControlApiClient.STATUS_PATH);

    responseByPath.put(ControlApiClient.STATUS_PATH,
        "{\"version\":" + "[".repeat(40) + "0" + "]".repeat(40)
            + ",\"uptimeSeconds\":1,\"dependencies\":[]}");
    RunResult excessiveNesting = run("status");
    assertThat(excessiveNesting.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(excessiveNesting.out()).isBlank();
    assertThat(excessiveNesting.err()).contains("response failed validation");
  }

  @Test
  void nonSuccessHttpBodyIsNotPrintedRaw() {
    responseStatus = 500;
    responseByPath.put(ControlApiClient.STATUS_PATH,
        "{\"stackTrace\":\"internal-host:5432 secret do-not-print\"}");

    RunResult result = run("status");

    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(result.out()).isBlank();
    assertThat(result.err())
        .contains("HTTP 500")
        .doesNotContain("internal-host")
        .doesNotContain("secret")
        .doesNotContain("do-not-print");
  }
  @Test
  void terminalControlCharactersAndOversizedStringsAreRejectedBeforePrinting() {
    responseByPath.put(ControlApiClient.STATUS_PATH,
        "{\"version\":\"bad\\u001b[31m\",\"uptimeSeconds\":1,\"dependencies\":[]}");
    RunResult control = run("status");
    assertThat(control.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(control.out()).isBlank();

    responseByPath.put(ControlApiClient.STATUS_PATH,
        "{\"version\":\"" + "x".repeat(129) + "\",\"uptimeSeconds\":1,\"dependencies\":[]}");
    RunResult oversized = run("status");
    assertThat(oversized.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
    assertThat(oversized.out()).isBlank();
  }

  @Test
  void validHealthAndDiagnosticsPrintNormalizedValidatedJson() {
    RunResult health = run("health");
    assertThat(health.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(health.out()).contains("\"status\":\"UP\"");

    RunResult diagnose = run("diagnose");
    assertThat(diagnose.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(diagnose.out()).contains("\"activeProfiles\"").contains("\"jvm\"");
  }

  @Test
  void noCommandEverLeaksTheSecretToOutput() {
    for (List<String> command : List.of(
        List.of("version"), List.of("config", "validate"), List.of("status"))) {
      RunResult result = run(command.toArray(String[]::new));
      assertThat(result.out() + result.err()).doesNotContain(SECRET);
    }
  }
}
