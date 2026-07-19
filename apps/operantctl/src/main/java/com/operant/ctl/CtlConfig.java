package com.operant.ctl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded operantctl configuration resolved from environment variables. The CLI carries only
 * operational intent and a credential reference; tenant, actor, permission, and secret material are
 * resolved by the credential/server boundary, not by client configuration.
 */
public record CtlConfig(
    String coreBaseUrl,
    String credentialAlias,
    ControlCredential controlCredential,
    int timeoutSeconds,
    boolean localMode,
    Path trustStorePath,
    char[] trustStorePassword) {

  static final String ENV_CORE_BASE_URL = "OPERANTCTL_CORE_BASE_URL";
  static final String ENV_CREDENTIAL_ALIAS = "OPERANTCTL_CREDENTIAL_ALIAS";
  static final String ENV_TIMEOUT_SECONDS = "OPERANTCTL_TIMEOUT_SECONDS";
  static final String ENV_MODE = "OPERANTCTL_MODE";
  static final String ENV_TRUST_STORE_PATH = "OPERANTCTL_TRUST_STORE_PATH";
  static final String ENV_TRUST_STORE_PASSWORD = "OPERANTCTL_TRUST_STORE_PASSWORD";

  private static final Pattern HTTPS_BASE_URL =
      Pattern.compile("^https://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?$");
  private static final Pattern LOCAL_HTTP_BASE_URL =
      Pattern.compile("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$");
  private static final Pattern CREDENTIAL_ALIAS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
  private static final int TIMEOUT_MIN = 1;
  private static final int TIMEOUT_MAX = 60;
  private static final int TIMEOUT_DEFAULT = 10;

  public CtlConfig {
    trustStorePassword = trustStorePassword == null ? null : trustStorePassword.clone();
  }

  @Override
  public char[] trustStorePassword() {
    return trustStorePassword == null ? null : trustStorePassword.clone();
  }

  /** Validation failure carrying every problem at once; messages never include secret material. */
  public static final class InvalidCtlConfigException extends RuntimeException {
    private final List<String> problems;

    InvalidCtlConfigException(List<String> problems) {
      super("Invalid operantctl configuration: " + String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  public static CtlConfig from(Map<String, String> env) {
    return from(env, ControlCredentialStore.production());
  }

  static CtlConfig from(Map<String, String> env, ControlCredentialStore credentialStore) {
    List<String> problems = new ArrayList<>();

    String mode = env.getOrDefault(ENV_MODE, "production").trim();
    boolean localMode = "local".equals(mode);
    if (!localMode && !"production".equals(mode)) {
      problems.add(ENV_MODE + " must be either production or local");
    }
    if (hasInsecureToggle(env)) {
      problems.add("TLS verification cannot be disabled");
    }

    String baseUrl = env.getOrDefault(ENV_CORE_BASE_URL, "").trim();
    if (!HTTPS_BASE_URL.matcher(baseUrl).matches()
        && !(localMode && LOCAL_HTTP_BASE_URL.matcher(baseUrl).matches())) {
      problems.add(ENV_CORE_BASE_URL
          + " must be https://host[:port] without path or userinfo; localhost http requires OPERANTCTL_MODE=local");
    }

    String credentialAlias = env.getOrDefault(ENV_CREDENTIAL_ALIAS, "").trim();
    if (!CREDENTIAL_ALIAS.matcher(credentialAlias).matches()) {
      problems.add(ENV_CREDENTIAL_ALIAS
          + " must be 1-64 characters of letters, digits, underscore, dash, or dot");
    }

    int timeoutSeconds = parseTimeout(env.get(ENV_TIMEOUT_SECONDS), problems);
    Path trustStorePath = parseTrustStorePath(env, problems);
    char[] trustStorePassword = parseTrustStorePassword(env, trustStorePath, problems);
    ControlCredential credential = null;
    if (problems.isEmpty()) {
      try {
        credential = credentialStore.load(credentialAlias);
      } catch (ControlCredentialStoreException unavailable) {
        problems.add(ENV_CREDENTIAL_ALIAS + " could not be loaded from the OS-protected store");
      }
    }

    if (!problems.isEmpty()) {
      if (trustStorePassword != null) {
        Arrays.fill(trustStorePassword, '\0');
      }
      throw new InvalidCtlConfigException(problems);
    }
    return new CtlConfig(baseUrl, credentialAlias, credential, timeoutSeconds, localMode, trustStorePath,
        trustStorePassword);
  }

  private static int parseTimeout(String raw, List<String> problems) {
    if (raw == null || raw.isBlank()) {
      return TIMEOUT_DEFAULT;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      if (value < TIMEOUT_MIN || value > TIMEOUT_MAX) {
        problems.add(ENV_TIMEOUT_SECONDS + " must be between " + TIMEOUT_MIN + " and " + TIMEOUT_MAX);
        return TIMEOUT_DEFAULT;
      }
      return value;
    } catch (NumberFormatException invalid) {
      problems.add(ENV_TIMEOUT_SECONDS + " must be a decimal integer");
      return TIMEOUT_DEFAULT;
    }
  }

  private static Path parseTrustStorePath(Map<String, String> env, List<String> problems) {
    String raw = env.getOrDefault(ENV_TRUST_STORE_PATH, "").trim();
    if (raw.isBlank()) {
      return null;
    }
    Path path = Path.of(raw).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path)) {
      problems.add(ENV_TRUST_STORE_PATH + " must point to a readable PKCS12 trust store file");
    }
    return path;
  }

  private static char[] parseTrustStorePassword(
      Map<String, String> env, Path trustStorePath, List<String> problems) {
    String raw = env.get(ENV_TRUST_STORE_PASSWORD);
    if (trustStorePath == null) {
      if (raw != null && !raw.isBlank()) {
        problems.add(ENV_TRUST_STORE_PASSWORD + " requires " + ENV_TRUST_STORE_PATH);
      }
      return null;
    }
    if (raw == null || raw.isBlank()) {
      problems.add(ENV_TRUST_STORE_PASSWORD + " is required when " + ENV_TRUST_STORE_PATH + " is set");
      return null;
    }
    return raw.toCharArray();
  }

  private static boolean hasInsecureToggle(Map<String, String> env) {
    for (String name : List.of("OPERANTCTL_INSECURE", "OPERANTCTL_SKIP_TLS_VERIFY", "OPERANTCTL_TLS_INSECURE")) {
      if (env.containsKey(name)) {
        return true;
      }
    }
    return false;
  }
}