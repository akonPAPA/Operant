package com.operant.ctl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;

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

  private static final ObjectMapper JSON = new ObjectMapper();

  private OperantCtl() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), Clock.systemUTC(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> env, Clock clock, PrintStream out, PrintStream err) {
    return run(args, env, ControlCredentialStore.production(), clock, out, err);
  }

  static int run(
      String[] args,
      Map<String, String> env,
      ControlCredentialStore credentialStore,
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
    out.println(response.body());
    if (negativeWhenFalseField != null) {
      try {
        JsonNode body = JSON.readTree(response.body());
        if (!body.path(negativeWhenFalseField).asBoolean(false)) {
          return EXIT_NEGATIVE;
        }
      } catch (Exception unparseable) {
        err.println("control API response was not parseable");
        return EXIT_NEGATIVE;
      }
    }
    return EXIT_OK;
  }

  private static int usage(PrintStream err) {
    err.println("usage: operantctl {version|config validate|status|health|readiness|diagnose}");
    err.println("exit codes: 0 ok, 1 negative result, 2 usage/config, 3 denied, 4 transport");
    return EXIT_USAGE_OR_CONFIG;
  }
}