package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bounded, fail-closed configuration proof - including that errors never echo secret material. */
class CtlConfigTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String ALIAS = "ops-prod";

  @TempDir Path tempDir;

  private InMemoryControlCredentialStore credentialStore;

  @BeforeEach
  void setUp() {
    credentialStore = new InMemoryControlCredentialStore();
    credentialStore.store(ALIAS, new ControlCredential(SECRET));
  }

  private Map<String, String> validEnv() {
    Map<String, String> env = new HashMap<>();
    env.put(CtlConfig.ENV_CORE_BASE_URL, "https://operant.internal.example");
    env.put(CtlConfig.ENV_CREDENTIAL_ALIAS, ALIAS);
    return env;
  }

  @Test
  void validConfigurationResolvesTypedFieldsFromCredentialReference() {
    CtlConfig config = CtlConfig.from(validEnv(), credentialStore);
    assertThat(config.coreBaseUrl()).isEqualTo("https://operant.internal.example");
    assertThat(config.credentialAlias()).isEqualTo(ALIAS);
    assertThat(HexFormat.of().formatHex(config.controlCredential().keyMaterialCopy())).isEqualTo(SECRET);
    assertThat(config.timeoutSeconds()).isEqualTo(10);
  }

  @Test
  void localhostHttpIsAllowedButRemoteHttpIsRejected() {
    Map<String, String> env = validEnv();
    env.put(CtlConfig.ENV_MODE, "local");
    env.put(CtlConfig.ENV_CORE_BASE_URL, "http://127.0.0.1:8080");
    assertThat(CtlConfig.from(env, credentialStore).coreBaseUrl()).isEqualTo("http://127.0.0.1:8080");

    env.put(CtlConfig.ENV_MODE, "production");
    env.put(CtlConfig.ENV_CORE_BASE_URL, "http://operant.internal.example");
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining(CtlConfig.ENV_CORE_BASE_URL);
  }

  @Test
  void baseUrlWithPathOrUserinfoIsRejected() {
    for (String bad : new String[] {
        "https://operant.example/api", "https://user:pass@operant.example", "ftp://operant.example",
        "https://operant.example/../", ""}) {
      Map<String, String> env = validEnv();
      env.put(CtlConfig.ENV_CORE_BASE_URL, bad);
      assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
          .as("base url '%s' must be rejected", bad)
          .isInstanceOf(CtlConfig.InvalidCtlConfigException.class);
    }
  }

  @Test
  void tenantAndActorEnvironmentVariablesAreNotPartOfTheContract() {
    Map<String, String> env = validEnv();
    env.put("OPERANTCTL_TENANT_ID", "not-a-uuid");
    env.put("OPERANTCTL_ACTOR_ID", "also-not-a-uuid");
    CtlConfig config = CtlConfig.from(env, credentialStore);
    assertThat(config.credentialAlias()).isEqualTo(ALIAS);
  }

  @Test
  void productionModeRejectsPlainHttpAndInsecureTlsToggles() {
    Map<String, String> env = validEnv();
    env.put(CtlConfig.ENV_CORE_BASE_URL, "http://127.0.0.1:8080");
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining(CtlConfig.ENV_CORE_BASE_URL);

    Map<String, String> insecureEnv = validEnv();
    insecureEnv.put("OPERANTCTL_INSECURE", "true");
    assertThatThrownBy(() -> CtlConfig.from(insecureEnv, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining("TLS verification cannot be disabled");
  }

  @Test
  void trustStoreConfigurationRequiresReadableFileAndPassword() throws Exception {
    Map<String, String> env = validEnv();
    Path trustStore = tempDir.resolve("truststore.p12");
    Files.writeString(trustStore, "not-a-real-store");
    env.put(CtlConfig.ENV_TRUST_STORE_PATH, trustStore.toString());
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining(CtlConfig.ENV_TRUST_STORE_PASSWORD);

    env.put(CtlConfig.ENV_TRUST_STORE_PASSWORD, "changeit");
    CtlConfig config = CtlConfig.from(env, credentialStore);
    assertThat(config.trustStorePath()).isEqualTo(trustStore.toAbsolutePath().normalize());
    assertThat(config.trustStorePassword()).containsExactly("changeit".toCharArray());
  }
  @Test
  void missingOrMalformedCredentialAliasIsRejected() {
    Map<String, String> env = validEnv();
    env.remove(CtlConfig.ENV_CREDENTIAL_ALIAS);
    CtlConfig.InvalidCtlConfigException missing =
        catchThrowableOfType(() -> CtlConfig.from(env, credentialStore), CtlConfig.InvalidCtlConfigException.class);
    assertThat(missing.problems()).anyMatch(problem -> problem.contains(CtlConfig.ENV_CREDENTIAL_ALIAS));

    env.put(CtlConfig.ENV_CREDENTIAL_ALIAS, "../prod");
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining(CtlConfig.ENV_CREDENTIAL_ALIAS);
  }

  @Test
  void missingCredentialIsRejectedWithoutEchoingAliasSecretMaterial() {
    Map<String, String> env = validEnv();
    env.put(CtlConfig.ENV_CREDENTIAL_ALIAS, "missing-prod");
    CtlConfig.InvalidCtlConfigException invalid =
        catchThrowableOfType(() -> CtlConfig.from(env, credentialStore), CtlConfig.InvalidCtlConfigException.class);
    assertThat(invalid.getMessage()).doesNotContain(SECRET);
    assertThat(invalid.problems())
        .anyMatch(problem -> problem.contains("could not be loaded"));
  }

  @Test
  void unsupportedStoreFailsClosedWithoutPlaintextFallback() {
    assertThatThrownBy(() -> CtlConfig.from(validEnv(), new UnsupportedProductionControlCredentialStore()))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining("OS-protected store");
  }

  @Test
  void windowsDpapiStoreRejectsPathTraversalAliasBeforeFilesystemAccess() {
    WindowsDpapiControlCredentialStore store = new WindowsDpapiControlCredentialStore(tempDir);
    assertThatThrownBy(() -> store.load("../prod"))
        .isInstanceOf(ControlCredentialStoreException.class)
        .hasMessageContaining("alias is invalid");
  }

  @Test
  void windowsDpapiStoreRejectsCorruptVersionedBlobWithoutPlaintextFallback() throws Exception {
    WindowsDpapiControlCredentialStore store = new WindowsDpapiControlCredentialStore(tempDir);
    String encodedAlias = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ALIAS.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.writeString(tempDir.resolve(encodedAlias + ".dpapi"), "not-the-supported-format");
    assertThatThrownBy(() -> store.load(ALIAS))
        .isInstanceOf(ControlCredentialStoreException.class)
        .hasMessageContaining("blob");
  }
  @Test
  void windowsDpapiStoreRoundTripsAndDeletesCredential() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
    WindowsDpapiControlCredentialStore store = new WindowsDpapiControlCredentialStore(tempDir);
    store.store(ALIAS, new ControlCredential(SECRET));

    assertThat(HexFormat.of().formatHex(store.load(ALIAS).keyMaterialCopy())).isEqualTo(SECRET);
    assertThat(store.metadata(ALIAS)).isPresent();
    byte[] stored = Files.walk(tempDir)
        .filter(path -> Files.isRegularFile(path))
        .findFirst()
        .map(path -> {
          try {
            return Files.readAllBytes(path);
          } catch (java.io.IOException failure) {
            throw new RuntimeException(failure);
          }
        })
        .orElseThrow();
    assertThat(new String(stored, java.nio.charset.StandardCharsets.US_ASCII)).doesNotContain(SECRET);

    store.delete(ALIAS);
    assertThat(store.metadata(ALIAS)).isEmpty();
    assertThatThrownBy(() -> store.load(ALIAS))
        .isInstanceOf(ControlCredentialStoreException.class)
        .hasMessageContaining("not available");
  }

  @Test
  void timeoutIsBounded() {
    Map<String, String> env = validEnv();
    env.put(CtlConfig.ENV_TIMEOUT_SECONDS, "0");
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class)
        .hasMessageContaining(CtlConfig.ENV_TIMEOUT_SECONDS);

    env.put(CtlConfig.ENV_TIMEOUT_SECONDS, "61");
    assertThatThrownBy(() -> CtlConfig.from(env, credentialStore))
        .isInstanceOf(CtlConfig.InvalidCtlConfigException.class);

    env.put(CtlConfig.ENV_TIMEOUT_SECONDS, "30");
    assertThat(CtlConfig.from(env, credentialStore).timeoutSeconds()).isEqualTo(30);
  }
}