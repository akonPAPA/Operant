package com.operant.ctl;

import java.io.Console;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P1-E operantctl - bounded Operant control-plane client (read-only slice).
 *
 * <p>Commands: {@code version}, {@code config validate}, {@code status}, {@code health},
 * {@code readiness}, {@code diagnose}. Lifecycle operations (backup/restore/upgrade/rollback) are a
 * later P1-E slice and are deliberately absent; an unknown command is a usage error, never a
 * passthrough.
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
    if (args.length != 1) {
      return usage(err);
    }
    return switch (command) {
      case "status" -> remoteRead(env, credentialStore, clock, out, err, "status", null);
      case "health" -> remoteRead(env, credentialStore, clock, out, err, "health", null);
      case "readiness" -> remoteRead(env, credentialStore, clock, out, err, "readiness", "ready");
      case "diagnose" -> remoteRead(env, credentialStore, clock, out, err, "diagnose", null);
      default -> usage(err);
    };
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
  private static int remoteRead(
      Map<String, String> env,
      ControlCredentialStore credentialStore,
      Clock clock,
      PrintStream out,
      PrintStream err,
      String command,
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
      response = client.get(path);
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
    err.println("usage: operantctl {version|config validate|credential import <alias> [--replace]|status|health|readiness|diagnose}");
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
