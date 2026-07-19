package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlApiClientTlsTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String ALIAS = "ops-prod";
  private static final String PASSWORD = "changeit";

  @TempDir Path tempDir;

  private final InMemoryControlCredentialStore credentialStore = new InMemoryControlCredentialStore();
  private HttpsServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void trustedTestCaAndMatchingHostnameSucceeds() throws Exception {
    CertificateMaterial material = certificate("localhost", "dns:localhost,ip:127.0.0.1", false);
    server = httpsServer(material.serverKeyStore(), "localhost");

    CtlConfig config = CtlConfig.from(env("https://localhost:" + server.getAddress().getPort(), material.trustStore()),
        credentialStore());
    ControlApiClient.ControlResponse response = new ControlApiClient(config, Clock.systemUTC())
        .get(ControlApiClient.HEALTH_PATH);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
  }

  @Test
  void untrustedCaFailsWithoutCredentialLeak() throws Exception {
    CertificateMaterial material = certificate("localhost", "dns:localhost,ip:127.0.0.1", false);
    server = httpsServer(material.serverKeyStore(), "localhost");

    CtlConfig config = CtlConfig.from(env("https://localhost:" + server.getAddress().getPort(), null),
        credentialStore());

    assertThatThrownBy(() -> new ControlApiClient(config, Clock.systemUTC()).get(ControlApiClient.HEALTH_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("SSLHandshakeException")
        .hasMessageNotContaining(SECRET)
        .hasMessageNotContaining("Authorization");
  }

  @Test
  void hostnameMismatchFails() throws Exception {
    CertificateMaterial material = certificate("localhost", "dns:localhost", false);
    server = httpsServer(material.serverKeyStore(), "127.0.0.1");

    CtlConfig config = CtlConfig.from(env("https://127.0.0.1:" + server.getAddress().getPort(), material.trustStore()),
        credentialStore());

    assertThatThrownBy(() -> new ControlApiClient(config, Clock.systemUTC()).get(ControlApiClient.HEALTH_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("SSLHandshakeException");
  }

  @Test
  void expiredLeafCertificateFails() throws Exception {
    CertificateMaterial material = certificate("localhost", "dns:localhost,ip:127.0.0.1", true);
    server = httpsServer(material.serverKeyStore(), "localhost");

    CtlConfig config = CtlConfig.from(env("https://localhost:" + server.getAddress().getPort(), material.trustStore()),
        credentialStore());

    assertThatThrownBy(() -> new ControlApiClient(config, Clock.systemUTC()).get(ControlApiClient.HEALTH_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("SSLHandshakeException");
  }

  @Test
  void unsupportedTlsConfigurationFailsClosed() throws Exception {
    Path invalidTrustStore = tempDir.resolve("invalid.p12");
    Files.writeString(invalidTrustStore, "not a pkcs12 trust store");
    CtlConfig config = CtlConfig.from(env("https://localhost:9443", invalidTrustStore), credentialStore());

    assertThatThrownBy(() -> new ControlApiClient(config, Clock.systemUTC()))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("control TLS configuration is invalid")
        .hasMessageNotContaining(SECRET);
  }

  private ControlCredentialStore credentialStore() {
    credentialStore.store(ALIAS, new ControlCredential(SECRET));
    return credentialStore;
  }

  private Map<String, String> env(String baseUrl, Path trustStore) {
    Map<String, String> env = new HashMap<>();
    env.put(CtlConfig.ENV_CORE_BASE_URL, baseUrl);
    env.put(CtlConfig.ENV_CREDENTIAL_ALIAS, ALIAS);
    if (trustStore != null) {
      env.put(CtlConfig.ENV_TRUST_STORE_PATH, trustStore.toString());
      env.put(CtlConfig.ENV_TRUST_STORE_PASSWORD, PASSWORD);
    }
    return env;
  }

  private HttpsServer httpsServer(Path keyStore, String bindHost) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keyStore)) {
      store.load(input, PASSWORD.toCharArray());
    }
    KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, PASSWORD.toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(factory.getKeyManagers(), null, null);

    HttpsServer https = HttpsServer.create(new InetSocketAddress(bindHost, 0), 0);
    https.setHttpsConfigurator(new HttpsConfigurator(context));
    https.createContext("/", exchange -> {
      byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    https.start();
    return https;
  }

  private CertificateMaterial certificate(String commonName, String san, boolean expired) throws Exception {
    Path caKeyStore = tempDir.resolve("ca-" + commonName + "-" + expired + ".p12");
    Path caCert = tempDir.resolve("ca-" + commonName + "-" + expired + ".cer");
    Path serverKeyStore = tempDir.resolve("server-" + commonName + "-" + expired + ".p12");
    Path serverRequest = tempDir.resolve("server-" + commonName + "-" + expired + ".csr");
    Path serverCert = tempDir.resolve("server-" + commonName + "-" + expired + ".cer");
    Path trustStore = tempDir.resolve("trust-" + commonName + "-" + expired + ".p12");

    runKeytool(List.of(keytool(), "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=Operant Test CA", "-ext", "bc:c", "-validity", "3650", "-storetype", "PKCS12",
        "-keystore", caKeyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"));
    runKeytool(List.of(keytool(), "-exportcert", "-alias", "ca", "-keystore", caKeyStore.toString(),
        "-storepass", PASSWORD, "-rfc", "-file", caCert.toString()));
    runKeytool(List.of(keytool(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=" + commonName, "-storetype", "PKCS12", "-keystore", serverKeyStore.toString(),
        "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"));
    runKeytool(List.of(keytool(), "-certreq", "-alias", "server", "-keystore", serverKeyStore.toString(),
        "-storepass", PASSWORD, "-file", serverRequest.toString()));
    java.util.ArrayList<String> sign = new java.util.ArrayList<>(List.of(keytool(), "-gencert", "-alias", "ca",
        "-keystore", caKeyStore.toString(), "-storepass", PASSWORD, "-infile", serverRequest.toString(),
        "-outfile", serverCert.toString(), "-rfc", "-ext", "SAN=" + san, "-ext", "KU=digitalSignature,keyEncipherment",
        "-ext", "EKU=serverAuth"));
    if (expired) {
      sign.addAll(List.of("-startdate", "2020/01/01 00:00:00", "-validity", "1"));
    } else {
      sign.addAll(List.of("-validity", "30"));
    }
    runKeytool(sign);
    runKeytool(List.of(keytool(), "-importcert", "-alias", "ca", "-keystore", serverKeyStore.toString(),
        "-storepass", PASSWORD, "-file", caCert.toString(), "-noprompt"));
    runKeytool(List.of(keytool(), "-importcert", "-alias", "server", "-keystore", serverKeyStore.toString(),
        "-storepass", PASSWORD, "-file", serverCert.toString(), "-noprompt"));
    runKeytool(List.of(keytool(), "-importcert", "-alias", "ca", "-storetype", "PKCS12",
        "-keystore", trustStore.toString(), "-storepass", PASSWORD, "-file", caCert.toString(), "-noprompt"));
    return new CertificateMaterial(serverKeyStore, trustStore);
  }

  private static String keytool() {
    String executable = System.getProperty("os.name", "").toLowerCase().contains("windows")
        ? "keytool.exe"
        : "keytool";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private static void runKeytool(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("keytool failed: " + output);
    }
  }

  private record CertificateMaterial(Path serverKeyStore, Path trustStore) {}
}