package com.operant.ctl;

import java.io.Console;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P1-E operantctl - bounded Operant control-plane client (read-only slice).
 *
 * <p>Commands: {@code version}, {@code config validate}, {@code status}, {@code health},
 * {@code readiness}, {@code diagnose}, and {@code operational-events} (documented alias {@code logs})
 * - which reads the bounded, cursor-paginated TYPED operational-event projection
 * ({@code operational-events != raw application logs}). The remaining lifecycle operations
 * (backup/restore/upgrade/rollback) are later P1-E slices and are deliberately absent; an unknown
 * command is a usage error, never a passthrough.
 */
public final class OperantCtl {
  static final String CTL_VERSION = "0.1.0";
  static final String PROTOCOL_VERSION = ControlPlaneSigner.SIGNATURE_VERSION;

  static final int EXIT_OK = 0;
  static final int EXIT_NEGATIVE = 1;
  static final int EXIT_USAGE_OR_CONFIG = 2;
  static final int EXIT_DENIED = 3;
  static final int EXIT_TRANSPORT = 4;
  private static final Pattern CREDENTIAL_ALIAS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
  private static final int CONTROL_SECRET_HEX_CHARS = 64;
  // P1-E lifecycle (operational-event slice): bounded, allowlisted operational-event query argument
  // shapes. Values are URL-safe by construction so the raw query string can be signed and placed on the
  // request line verbatim. The externally simple verb is `logs`, but it reads bounded TYPED operational
  // events (not raw application logs).
  private static final Pattern EVENT_SEVERITY = Pattern.compile("^(ERROR|WARN|INFO)$");
  private static final Pattern EVENT_COMPONENT = Pattern.compile("^(DATABASE|REDIS|PLATFORM)$");
  private static final Pattern EVENT_CODE = Pattern.compile("^(DEPENDENCY_STATE_CHANGED|READINESS_STATE_CHANGED)$");
  private static final Pattern EVENT_BEFORE = Pattern.compile("^[0-9]{1,19}$");
  private static final int EVENT_MAX_LIMIT = 100;

  private OperantCtl() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), Clock.systemUTC(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> env, Clock clock, PrintStream out, PrintStream err) {
    return run(args, env, ControlCredentialStore.production(), ConsoleSecretReader.INSTANCE, clock, out, err);
  }

  static int run(
      String[] args,
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      Clock clock,
      PrintStream out,
      PrintStream err) {
    return run(args, env, credentialStore, ConsoleSecretReader.INSTANCE, clock, out, err);
  }

  static int run(
      String[] args,
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      SecretReader secretReader,
      Clock clock,
      PrintStream out,
      PrintStream err) {
    if (args.length == 0) {
      return usage(err);
    }
    String command = args[0];
    if ("version".equals(command) && args.length == 1) {
      out.println("operantctl " + CTL_VERSION + " (control protocol v" + PROTOCOL_VERSION + ")");
      return EXIT_OK;
    }
    if ("config".equals(command)) {
      if (args.length == 2 && "validate".equals(args[1])) {
        return configValidate(env, credentialStore, out, err);
      }
      return usage(err);
    }
    if ("credential".equals(command)) {
      if (args.length == 3 && "import".equals(args[1])) {
        return importCredential(args[2], false, credentialStore, secretReader, out, err);
      }
      if (args.length == 4 && "import".equals(args[1]) && "--replace".equals(args[3])) {
        return importCredential(args[2], true, credentialStore, secretReader, out, err);
      }
      return usage(err);
    }
    // Canonical verb `operational-events`; `logs` is a documented alias for the SAME bounded typed
    // operational-event read (identical route, permission and response contract - never raw app logs).
    if ("operational-events".equals(command) || "logs".equals(command)) {
      return logsCommand(args, env, credentialStore, clock, out, err);
    }
    if (args.length != 1) {
      return usage(err);
    }
    return switch (command) {
      case "status" -> remoteRead(env, credentialStore, clock, out, err, "status", "", null);
      case "health" -> remoteRead(env, credentialStore, clock, out, err, "health", "", null);
      case "readiness" -> remoteRead(env, credentialStore, clock, out, err, "readiness", "", "ready");
      case "diagnose" -> remoteRead(env, credentialStore, clock, out, err, "diagnose", "", null);
      default -> usage(err);
    };
  }

  /**
   * P1-E lifecycle (operational-event slice): {@code logs [--severity S] [--component C]
   * [--event-code E] [--limit N] [--before SEQ]}. Reads bounded TYPED operational events, not raw
   * application logs. All flags are optional, each at most once, each validated to a bounded
   * allowlisted shape here BEFORE any request is built. The raw query is assembled in a fixed order
   * from URL-safe tokens only.
   */
  private static int logsCommand(
      String[] args,
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      Clock clock,
      PrintStream out,
      PrintStream err) {
    String severity = null;
    String component = null;
    String eventCode = null;
    String limit = null;
    String before = null;
    int index = 1;
    while (index < args.length) {
      String flag = args[index];
      if (index + 1 >= args.length) {
        return usage(err);
      }
      String value = args[index + 1];
      switch (flag) {
        case "--severity" -> {
          if (severity != null) {
            return usage(err);
          }
          severity = value;
        }
        case "--component" -> {
          if (component != null) {
            return usage(err);
          }
          component = value;
        }
        case "--event-code" -> {
          if (eventCode != null) {
            return usage(err);
          }
          eventCode = value;
        }
        case "--limit" -> {
          if (limit != null) {
            return usage(err);
          }
          limit = value;
        }
        case "--before" -> {
          if (before != null) {
            return usage(err);
          }
          before = value;
        }
        default -> {
          return usage(err);
        }
      }
      index += 2;
    }

    StringBuilder rawQuery = new StringBuilder();
    if (severity != null) {
      String normalized = severity.toUpperCase(Locale.ROOT);
      if (!EVENT_SEVERITY.matcher(normalized).matches()) {
        err.println("logs: severity is invalid");
        return EXIT_USAGE_OR_CONFIG;
      }
      appendQuery(rawQuery, "severity", normalized);
    }
    if (component != null) {
      String normalized = component.toUpperCase(Locale.ROOT);
      if (!EVENT_COMPONENT.matcher(normalized).matches()) {
        err.println("logs: component is invalid");
        return EXIT_USAGE_OR_CONFIG;
      }
      appendQuery(rawQuery, "component", normalized);
    }
    if (eventCode != null) {
      String normalized = eventCode.toUpperCase(Locale.ROOT);
      if (!EVENT_CODE.matcher(normalized).matches()) {
        err.println("logs: event-code is invalid");
        return EXIT_USAGE_OR_CONFIG;
      }
      appendQuery(rawQuery, "eventCode", normalized);
    }
    if (limit != null) {
      Integer parsed = parseEventLimit(limit);
      if (parsed == null) {
        err.println("logs: limit must be an integer between 1 and " + EVENT_MAX_LIMIT);
        return EXIT_USAGE_OR_CONFIG;
      }
      appendQuery(rawQuery, "limit", Integer.toString(parsed));
    }
    if (before != null) {
      if (!EVENT_BEFORE.matcher(before).matches()) {
        err.println("logs: before is invalid");
        return EXIT_USAGE_OR_CONFIG;
      }
      appendQuery(rawQuery, "before", before);
    }
    return remoteRead(env, credentialStore, clock, out, err, "operational-events", rawQuery.toString(), null);
  }

  private static Integer parseEventLimit(String limit) {
    if (limit.isEmpty() || limit.length() > 3) {
      return null;
    }
    for (int i = 0; i < limit.length(); i++) {
      if (limit.charAt(i) < '0' || limit.charAt(i) > '9') {
        return null;
      }
    }
    int value = Integer.parseInt(limit);
    return value >= 1 && value <= EVENT_MAX_LIMIT ? value : null;
  }

  private static void appendQuery(StringBuilder rawQuery, String key, String value) {
    if (rawQuery.length() > 0) {
      rawQuery.append('&');
    }
    rawQuery.append(key).append('=').append(value);
  }

  private static int configValidate(
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      PrintStream out,
      PrintStream err) {
    try {
      CtlConfig config = CtlConfig.from(env, credentialStore);
      out.println("operantctl configuration is valid");
      out.println("core: " + config.coreBaseUrl());
      out.println("credential: " + config.credentialAlias());
      out.println("timeoutSeconds: " + config.timeoutSeconds());
      return EXIT_OK;
    } catch (CtlConfig.InvalidCtlConfigException invalid) {
      invalid.problems().forEach(problem -> err.println("config: " + problem));
      return EXIT_USAGE_OR_CONFIG;
    }
  }


  private static int importCredential(
      String alias,
      boolean replace,
      ControlCredentialStore credentialStore,
      SecretReader secretReader,
      PrintStream out,
      PrintStream err) {
    if (alias == null || alias.isBlank() || !alias.equals(alias.trim()) || !CREDENTIAL_ALIAS.matcher(alias).matches()) {
      err.println("credential import failed: alias is invalid");
      return EXIT_USAGE_OR_CONFIG;
    }
    String resolvedAlias = alias;
    try {
      if (!replace && credentialStore.metadata(resolvedAlias).isPresent()) {
        err.println("credential import failed: credential already exists; use --replace to overwrite");
        return EXIT_USAGE_OR_CONFIG;
      }
      char[] secret = secretReader.readSecret("Control credential secret: ");
      char[] confirmation = secretReader.readSecret("Confirm control credential secret: ");
      try {
        if (secret == null || confirmation == null) {
          err.println("credential import failed: secret input is unavailable");
          return EXIT_USAGE_OR_CONFIG;
        }
        if (!Arrays.equals(secret, confirmation)) {
          err.println("credential import failed: confirmation does not match");
          return EXIT_USAGE_OR_CONFIG;
        }
        if (!isControlCredentialSecret(secret)) {
          err.println("credential import failed: secret is invalid");
          return EXIT_USAGE_OR_CONFIG;
        }
        try (ControlCredential credential = new ControlCredential(new String(secret))) {
          credentialStore.store(resolvedAlias, credential);
        }
        out.println("control credential imported: " + resolvedAlias);
        return EXIT_OK;
      } finally {
        if (secret != null) {
          Arrays.fill(secret, '\0');
        }
        if (confirmation != null) {
          Arrays.fill(confirmation, '\0');
        }
      }
    } catch (ControlCredentialStoreException failure) {
      err.println("credential import failed: " + failure.getMessage());
      return EXIT_USAGE_OR_CONFIG;
    }
  }
  private static boolean isControlCredentialSecret(char[] secret) {
    if (secret.length != CONTROL_SECRET_HEX_CHARS) {
      return false;
    }
    for (char value : secret) {
      boolean hex = (value >= '0' && value <= '9')
          || (value >= 'a' && value <= 'f')
          || (value >= 'A' && value <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }
  private static int remoteRead(
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      Clock clock,
      PrintStream out,
      PrintStream err,
      String command,
      String rawQuery,
      String negativeWhenFalseField) {
    CtlConfig config;
    try {
      config = CtlConfig.from(env, credentialStore);
    } catch (CtlConfig.InvalidCtlConfigException invalid) {
      invalid.problems().forEach(problem -> err.println("config: " + problem));
      return EXIT_USAGE_OR_CONFIG;
    }
    ControlApiClient.ControlResponse response;
    try {
      ControlApiClient client = new ControlApiClient(config, clock);
      String path = client.commandPaths().get(command);
      response = client.get(path, rawQuery);
    } catch (ControlApiClient.ControlTransportException transport) {
      err.println(transport.getMessage());
      return EXIT_TRANSPORT;
    }
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      err.println("control API denied the request (HTTP " + response.statusCode() + ")");
      return EXIT_DENIED;
    }
    if (response.statusCode() != 200) {
      err.println("control API returned HTTP " + response.statusCode());
      return EXIT_NEGATIVE;
    }
    ControlResponseValidator.ValidatedResponse validated;
    try {
      validated = ControlResponseValidator.validate(command, response.body());
    } catch (ControlResponseValidator.InvalidControlResponseException
        | ControlApiClient.InvalidControlResponseException invalid) {
      err.println("control API response failed validation");
      return EXIT_NEGATIVE;
    }
    out.println(validated.normalizedJson());
    if (negativeWhenFalseField != null && !validated.ready()) {
      return EXIT_NEGATIVE;
    }
    return EXIT_OK;
  }

  private static int usage(PrintStream err) {
    err.println("usage: operantctl {version|config validate|credential import <alias> [--replace]"
        + "|status|health|readiness|diagnose"
        + "|operational-events [--severity S] [--component C] [--event-code E] [--limit N] [--before SEQ]"
        + " (alias: logs)}");
    err.println("exit codes: 0 ok, 1 negative result, 2 usage/config, 3 denied, 4 transport");
    return EXIT_USAGE_OR_CONFIG;
  }
  interface SecretReader {
    char[] readSecret(String prompt);
  }

  enum ConsoleSecretReader implements SecretReader {
    INSTANCE;

    @Override
    public char[] readSecret(String prompt) {
      Console console = System.console();
      if (console == null) {
        return null;
      }
      return console.readPassword("%s", prompt);
    }
  }
}
