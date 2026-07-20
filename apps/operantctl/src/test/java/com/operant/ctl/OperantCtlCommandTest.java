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
import java.util.HexFormat;
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
  private static final String REPLACEMENT_SECRET =
      "11112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String VALID_STATUS =
      "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"NOT_CONFIGURED\"}]}";
  private static final String VALID_HEALTH = "{\"status\":\"UP\"}";
  private static final String VALID_READY =
      "{\"ready\":true,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"NOT_CONFIGURED\"}]}";
  private static final String VALID_DIAGNOSTICS =
      "{\"version\":\"unknown\",\"activeProfiles\":[\"production\"],\"database\":{\"state\":\"UP\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":true,\"state\":\"UP\"},\"jvm\":{\"heapUsedMb\":1,\"heapMaxMb\":256}}";

  private static final String VALID_EVENTS =
      "{\"events\":[{\"occurredAt\":\"2026-07-19T10:00:00Z\",\"eventCode\":\"DEPENDENCY_STATE_CHANGED\","
          + "\"component\":\"DATABASE\",\"severity\":\"ERROR\","
          + "\"summary\":\"dependency DATABASE state changed to DOWN\",\"correlationId\":null},"
          + "{\"occurredAt\":\"2026-07-19T09:59:00Z\",\"eventCode\":\"READINESS_STATE_CHANGED\","
          + "\"component\":\"PLATFORM\",\"severity\":\"WARN\","
          + "\"summary\":\"platform readiness changed to NOT_READY\",\"correlationId\":\"corr-01\"}],"
          + "\"nextCursor\":\"11\",\"hasMore\":true,\"returned\":2,\"maxLimit\":100,"
          + "\"scope\":\"LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS\","
          + "\"instanceId\":\"11111111-2222-3333-4444-555555555555\"}";

  private HttpServer server;
  private Map<String, String> env;
  private InMemoryControlCredentialStore credentialStore;
  private final Map<String, Map<String, String>> capturedHeadersByPath = new ConcurrentHashMap<>();
  private final Map<String, String> capturedQueryByPath = new ConcurrentHashMap<>();
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
      String rawQuery = exchange.getRequestURI().getRawQuery();
      capturedQueryByPath.put(exchange.getRequestURI().getPath(), rawQuery == null ? "" : rawQuery);
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
    responseByPath.put(ControlApiClient.OPERATIONAL_EVENTS_PATH, VALID_EVENTS);
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

  private RunResult runWithReader(OperantCtl.SecretReader reader, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = OperantCtl.run(
        args,
        env,
        credentialStore,
        reader,
        Clock.systemUTC(),
        new PrintStream(out, true),
        new PrintStream(err, true));
    return new RunResult(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
  @Test
  void credentialImportStoresNewCredentialWithoutPrintingSecret() {
    credentialStore.delete("new-ops");
    RunResult result = runWithReader(prompt -> SECRET.toCharArray(), "credential", "import", "new-ops");

    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("new-ops").doesNotContain(SECRET);
    assertThat(result.err()).doesNotContain(SECRET);
    assertThat(credentialStore.metadata("new-ops")).isPresent();
  }

  @Test
  void credentialImportRequiresValidAliasMatchingConfirmationAndExplicitReplace() {
    RunResult invalidAlias = runWithReader(prompt -> SECRET.toCharArray(), "credential", "import", " bad");
    assertThat(invalidAlias.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);

    RunResult mismatch = runWithReader(new SequenceSecretReader(SECRET,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        "credential", "import", "new-mismatch");
    assertThat(mismatch.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(mismatch.out() + mismatch.err()).doesNotContain(SECRET);

    RunResult invalidSecret = runWithReader(prompt -> "not-a-secret".toCharArray(), "credential", "import", "new-invalid");
    assertThat(invalidSecret.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(invalidSecret.out() + invalidSecret.err()).doesNotContain("not-a-secret");

    RunResult emptySecret = runWithReader(prompt -> "".toCharArray(), "credential", "import", "new-empty");
    assertThat(emptySecret.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);

    String oversizedSecret = SECRET + "00";
    RunResult oversized = runWithReader(prompt -> oversizedSecret.toCharArray(), "credential", "import", "new-large");
    assertThat(oversized.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(oversized.out() + oversized.err()).doesNotContain(oversizedSecret);

    String secretWithControl = SECRET.substring(0, 32) + "\r\n" + SECRET.substring(34);
    RunResult controlCharacter = runWithReader(
        prompt -> secretWithControl.toCharArray(), "credential", "import", "new-control");
    assertThat(controlCharacter.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(controlCharacter.out() + controlCharacter.err()).doesNotContain(secretWithControl);

    RunResult existing = runWithReader(prompt -> SECRET.toCharArray(), "credential", "import", ALIAS);
    assertThat(existing.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);

    RunResult replaced = runWithReader(prompt -> SECRET.toCharArray(), "credential", "import", ALIAS, "--replace");
    assertThat(replaced.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
  }

  @Test
  void failedReplacementPreservesExistingCredential() {
    InMemoryControlCredentialStore delegate = new InMemoryControlCredentialStore();
    delegate.store(ALIAS, new ControlCredential(SECRET));
    ControlCredentialStore rejectingStore = new ControlCredentialStore() {
      @Override public ControlCredential load(String alias) { return delegate.load(alias); }
      @Override public void store(String alias, ControlCredential credential) { throw new ControlCredentialStoreException("store failed"); }
      @Override public void delete(String alias) { delegate.delete(alias); }
      @Override public java.util.Optional<ControlCredentialMetadata> metadata(String alias) { return delegate.metadata(alias); }
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    int exit = OperantCtl.run(new String[] {"credential", "import", ALIAS, "--replace"}, env, rejectingStore,
        prompt -> REPLACEMENT_SECRET.toCharArray(), Clock.systemUTC(), new PrintStream(out, true), new PrintStream(err, true));

    assertThat(exit).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
        .doesNotContain(REPLACEMENT_SECRET)
        .doesNotContain(SECRET);
    ControlCredential preserved = delegate.load(ALIAS);
    try {
      assertThat(HexFormat.of().formatHex(preserved.keyMaterialCopy())).isEqualTo(SECRET);
    } finally {
      preserved.close();
    }
  }
  @Test
  void credentialImportHandlesStoreAndConsoleFailuresWithoutSecretLeak() {
    RunResult noConsole = runWithReader(prompt -> null, "credential", "import", "new-null");
    assertThat(noConsole.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);

    ControlCredentialStore failingStore = new ControlCredentialStore() {
      @Override public ControlCredential load(String alias) { throw new ControlCredentialStoreException("load failed"); }
      @Override public void store(String alias, ControlCredential credential) { throw new ControlCredentialStoreException("store failed"); }
      @Override public void delete(String alias) {}
      @Override public java.util.Optional<ControlCredentialMetadata> metadata(String alias) { return java.util.Optional.empty(); }
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = OperantCtl.run(new String[] {"credential", "import", "new-fail"}, env, failingStore,
        prompt -> SECRET.toCharArray(), Clock.systemUTC(), new PrintStream(out, true), new PrintStream(err, true));
    assertThat(exit).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
    assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
  }

  @Test
  void semanticResponseContradictionsAreRejectedBeforePrinting() {
    for (String invalid : List.of(
        "{\"ready\":true,\"dependencies\":[]}",
        "{\"ready\":true,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"database\",\"state\":\"UP\"}]}",
        "{\"ready\":true,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"DOWN\"}]}",
        "{\"version\":\"unknown\",\"uptimeSeconds\":1,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"UP\"},{\"name\":\"extra\",\"state\":\"UP\"}]}",
        "{\"version\":\"http://internal\",\"uptimeSeconds\":1,\"dependencies\":[{\"name\":\"database\",\"state\":\"UP\"},{\"name\":\"redis\",\"state\":\"UP\"}]}",
        "{\"version\":\"unknown\",\"activeProfiles\":[\"production\",\"production\"],\"database\":{\"state\":\"UP\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":false,\"state\":\"UP\"},\"jvm\":{\"heapUsedMb\":1,\"heapMaxMb\":256}}",
        "{\"version\":\"unknown\",\"activeProfiles\":[\"production\"],\"database\":{\"state\":\"UP\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":true,\"state\":\"NOT_CONFIGURED\"},\"jvm\":{\"heapUsedMb\":1,\"heapMaxMb\":256}}",
        "{\"version\":\"unknown\",\"activeProfiles\":[\"production\"],\"database\":{\"state\":\"UP\",\"migrationVersion\":\"65\"},\"redis\":{\"configured\":true,\"state\":\"UP\"},\"jvm\":{\"heapUsedMb\":257,\"heapMaxMb\":256}}")) {
      responseByPath.put(ControlApiClient.READINESS_PATH, invalid);
      responseByPath.put(ControlApiClient.STATUS_PATH, invalid);
      responseByPath.put(ControlApiClient.DIAGNOSTICS_PATH, invalid);
      RunResult result = invalid.contains("ready") ? run("readiness") : invalid.contains("uptimeSeconds") ? run("status") : run("diagnose");
      assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
      assertThat(result.out()).isBlank();
      assertThat(result.err()).contains("response failed validation");
    }
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
    responseByPath.put(ControlApiClient.READINESS_PATH, VALID_READY);
    assertThat(run("readiness").exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    responseByPath.put(ControlApiClient.READINESS_PATH,
        "{\"ready\":false,\"dependencies\":[{\"name\":\"database\",\"state\":\"DOWN\"},{\"name\":\"redis\",\"state\":\"NOT_CONFIGURED\"}]}");
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
  void logsSendsBoundedSignedOperationalEventQueryAndValidatesResponse() {
    RunResult result = run(
        "logs", "--severity", "error", "--component", "database",
        "--event-code", "dependency_state_changed", "--limit", "2", "--before", "100");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("\"events\"").contains("\"maxLimit\":100");

    // The client builds the query in a fixed order from allowlisted URL-safe tokens...
    String query = capturedQueryByPath.get(ControlApiClient.OPERATIONAL_EVENTS_PATH);
    assertThat(query).isEqualTo(
        "severity=ERROR&component=DATABASE&eventCode=DEPENDENCY_STATE_CHANGED&limit=2&before=100");

    // ...and signs exactly that raw query (the server verifies the signature over it verbatim).
    Map<String, String> headers = capturedHeadersByPath.get(ControlApiClient.OPERATIONAL_EVENTS_PATH);
    assertThat(headers).isNotNull();
    assertThat(headers).doesNotContainKeys("X-tenant-id", "X-orderpilot-permissions");
    ControlPlaneSigner signer = new ControlPlaneSigner(new ControlCredential(SECRET).keyMaterialCopy(), ALIAS);
    String canonical = ControlPlaneSigner.canonical(
        "GET",
        ControlApiClient.OPERATIONAL_EVENTS_PATH,
        query,
        "",
        headers.get("X-orderpilot-control-content-sha256"),
        headers.get("X-orderpilot-control-audience"),
        ALIAS,
        Long.parseLong(headers.get("X-orderpilot-control-timestamp")),
        headers.get("X-orderpilot-control-nonce"));
    assertThat(headers.get("X-orderpilot-control-signature")).isEqualTo(signer.hmacHex(canonical));
  }

  @Test
  void logsWithoutArgumentsSendsAnEmptyQuery() {
    RunResult result = run("logs");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(capturedQueryByPath.get(ControlApiClient.OPERATIONAL_EVENTS_PATH)).isEmpty();
  }

  @Test
  void operationalEventsCanonicalVerbBehavesIdenticallyToLogsAlias() {
    // The canonical `operational-events` verb reads the SAME bounded typed projection as the `logs`
    // alias: identical route, identical allowlisted query construction, identical signed request.
    RunResult result = run("operational-events", "--severity", "error", "--component", "database");
    assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_OK);
    assertThat(result.out()).contains("\"events\"");
    assertThat(capturedQueryByPath.get(ControlApiClient.OPERATIONAL_EVENTS_PATH))
        .isEqualTo("severity=ERROR&component=DATABASE");
  }

  @Test
  void logsRejectsInvalidArgumentsBeforeSendingAnyRequest() {
    for (String[] args : List.of(
        new String[] {"logs", "--severity", "BOGUS"},
        new String[] {"logs", "--severity", "DEBUG"},
        new String[] {"logs", "--component", "KAFKA"},
        new String[] {"logs", "--event-code", "BACKUP_STARTED"},
        new String[] {"logs", "--limit", "0"},
        new String[] {"logs", "--limit", "101"},
        new String[] {"logs", "--limit", "abc"},
        new String[] {"logs", "--before", "not-a-number"},
        new String[] {"logs", "--before", "-5"},
        new String[] {"logs", "--unknown", "x"},
        new String[] {"logs", "--severity"},
        new String[] {"logs", "--severity", "WARN", "--severity", "INFO"},
        new String[] {"logs", "extra-positional"})) {
      capturedHeadersByPath.clear();
      RunResult result = run(args);
      assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_USAGE_OR_CONFIG);
      assertThat(capturedHeadersByPath).doesNotContainKey(ControlApiClient.OPERATIONAL_EVENTS_PATH);
    }
  }

  @Test
  void logsRejectsInvalidResponseBodiesBeforePrinting() {
    for (String invalid : List.of(
        "{}",
        // returned disagrees with events size
        "{\"events\":[],\"nextCursor\":null,\"hasMore\":false,\"returned\":1,\"maxLimit\":100,"
            + "\"scope\":\"LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS\",\"instanceId\":\"11111111-2222-3333-4444-555555555555\"}",
        // hasMore true but no cursor (asymmetric)
        "{\"events\":[],\"nextCursor\":null,\"hasMore\":true,\"returned\":0,\"maxLimit\":100,"
            + "\"scope\":\"LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS\",\"instanceId\":\"11111111-2222-3333-4444-555555555555\"}",
        // wrong scope token
        "{\"events\":[],\"nextCursor\":null,\"hasMore\":false,\"returned\":0,\"maxLimit\":100,"
            + "\"scope\":\"LOCAL_PROCESS_RECENT_LOGS\",\"instanceId\":\"11111111-2222-3333-4444-555555555555\"}",
        // unknown event code token
        "{\"events\":[{\"occurredAt\":\"2026-07-19T10:00:00Z\",\"eventCode\":\"BACKUP_STARTED\","
            + "\"component\":\"DATABASE\",\"severity\":\"INFO\",\"summary\":\"x\",\"correlationId\":null}],"
            + "\"nextCursor\":null,\"hasMore\":false,\"returned\":1,\"maxLimit\":100,"
            + "\"scope\":\"LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS\",\"instanceId\":\"11111111-2222-3333-4444-555555555555\"}",
        // forbidden field marker in the payload
        "{\"events\":[],\"nextCursor\":null,\"hasMore\":false,\"returned\":0,\"maxLimit\":100,"
            + "\"scope\":\"LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS\",\"instanceId\":\"11111111-2222-3333-4444-555555555555\","
            + "\"secret\":\"leak\"}")) {
      responseByPath.put(ControlApiClient.OPERATIONAL_EVENTS_PATH, invalid);
      RunResult result = run("logs");
      assertThat(result.exitCode()).isEqualTo(OperantCtl.EXIT_NEGATIVE);
      assertThat(result.out()).isBlank();
      assertThat(result.err()).contains("response failed validation").doesNotContain("leak");
    }
  }

  @Test
  void noCommandEverLeaksTheSecretToOutput() {
    for (List<String> command : List.of(
        List.of("version"), List.of("config", "validate"), List.of("status"))) {
      RunResult result = run(command.toArray(String[]::new));
      assertThat(result.out() + result.err()).doesNotContain(SECRET);
    }
  }

  private static final class SequenceSecretReader implements OperantCtl.SecretReader {
    private final String[] values;
    private int index;

    private SequenceSecretReader(String... values) {
      this.values = values;
    }

    @Override
    public char[] readSecret(String prompt) {
      return values[index++].toCharArray();
    }
  }
}
