import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD_FLAG = "ORDERPILOT_OIDC_RUNTIME_TEST_CHILD";

if (process.env[CHILD_FLAG] !== "1") {
  test("C1B OIDC runtime contract passes under the server-only condition", () => {
    const result = spawnSync(process.execPath, ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)], {
      cwd: process.cwd(),
      env: { ...process.env, [CHILD_FLAG]: "1" },
      encoding: "utf8"
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const configModule = await import("../lib/bff/bff-oidc-config.ts");
  const runtimeModule = await import("../lib/bff/bff-oidc-runtime.ts");

  const {
    readOidcConfigurationStatus,
    validOidcConfiguration,
    OIDC_RUNTIME_IMPLEMENTED
  } = configModule;
  const {
    createOidcRuntimeCache,
    loadOidcProviderRuntime,
    oidcRuntimeDiscoveryPolicy
  } = runtimeModule;

  const VALID_SECRET = "c1b-valid-secret-value-0123456789abcdef";
  const VALID_CLIENT_ID = "operant-dashboard-client";
  const VALID_ISSUER = "https://idp.example.test/tenant-a";
  const VALID_PUBLIC_ORIGIN = "https://dashboard.example.test";

  function validEnv(overrides = {}) {
    return {
      ORDERPILOT_DEPLOY_PROFILE: "prod",
      NODE_ENV: "test",
      ORDERPILOT_PUBLIC_ORIGIN: VALID_PUBLIC_ORIGIN,
      ORDERPILOT_OIDC_ENABLED: "true",
      ORDERPILOT_OIDC_ISSUER: VALID_ISSUER,
      ORDERPILOT_OIDC_CLIENT_ID: VALID_CLIENT_ID,
      ORDERPILOT_OIDC_CLIENT_SECRET: VALID_SECRET,
      ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback`,
      ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/login`,
      ORDERPILOT_OIDC_SCOPES: "openid profile email",
      ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "CLIENT_SECRET_BASIC",
      ...overrides
    };
  }

  function validConfiguration(overrides = {}) {
    const status = readOidcConfigurationStatus(validEnv(overrides));
    const configuration = validOidcConfiguration(status);
    assert.ok(configuration, "expected valid OIDC configuration");
    return configuration;
  }

  function providerMetadata(overrides = {}) {
    return {
      issuer: VALID_ISSUER,
      authorization_endpoint: `${VALID_ISSUER}/oauth2/v1/authorize`,
      token_endpoint: `${VALID_ISSUER}/oauth2/v1/token`,
      jwks_uri: `${VALID_ISSUER}/oauth2/v1/keys`,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["client_secret_basic"],
      scopes_supported: ["openid", "profile", "email"],
      id_token_signing_alg_values_supported: ["RS256", "HS256"],
      subject_types_supported: ["public"],
      ...overrides
    };
  }

  function jsonResponse(body, init = {}) {
    return new Response(typeof body === "string" ? body : JSON.stringify(body), {
      status: init.status ?? 200,
      headers: {
        "content-type": init.contentType ?? "application/json",
        ...(init.contentLength ? { "content-length": init.contentLength } : {})
      }
    });
  }

  function metadataFetch(metadata, calls = []) {
    return async (url, init = {}) => {
      calls.push({ url, init });
      return jsonResponse(metadata);
    };
  }

  function expectError(result, code) {
    assert.equal(result.ok, false);
    assert.equal(result.error.code, code);
    assert.doesNotMatch(JSON.stringify(result.error), new RegExp(VALID_SECRET));
    assert.doesNotMatch(JSON.stringify(result.error), new RegExp(VALID_CLIENT_ID));
  }

  test("C1B runtime accepts only branded C1A-valid configuration", async () => {
    const disabledStatus = readOidcConfigurationStatus({});
    expectError(await loadOidcProviderRuntime(disabledStatus, { fetch: metadataFetch(providerMetadata()) }), "OIDC_CONFIGURATION_INVALID");

    const status = readOidcConfigurationStatus(validEnv());
    assert.equal(status.state, "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED");
    expectError(await loadOidcProviderRuntime(status.configuration, { fetch: metadataFetch(providerMetadata()) }), "OIDC_CONFIGURATION_INVALID");

    const configuration = validOidcConfiguration(status);
    assert.ok(configuration);
    assert.throws(() => JSON.stringify(configuration), /OIDC_CONFIGURATION_NOT_PUBLIC/);
  });

  test("C1B runtime validates provider metadata without enabling login, callback, tokens, or sessions", async () => {
    const calls = [];
    const configuration = validConfiguration();
    const result = await loadOidcProviderRuntime(configuration, {
      fetch: metadataFetch(providerMetadata(), calls),
      cache: createOidcRuntimeCache(),
      now: () => 1_000,
      cacheTtlMs: 30_000
    });

    assert.equal(result.ok, true);
    assert.equal(result.runtime.status, "PROVIDER_METADATA_VALIDATED_FOR_FUTURE_AUTHORIZATION_CODE_FLOW");
    assert.equal(result.runtime.issuer, VALID_ISSUER);
    assert.equal(result.runtime.authorizationEndpoint, `${VALID_ISSUER}/oauth2/v1/authorize`);
    assert.deepEqual(result.runtime.supportedScopes, ["openid", "profile", "email"]);
    assert.deepEqual(result.runtime.idTokenSigningAlgorithms, ["RS256"]);
    assert.equal(result.runtime.clientAuthenticationMethod, "client_secret_basic");
    assert.equal(result.runtime.expiresAtEpochMs, 31_000);
    assert.throws(() => JSON.stringify(result.runtime), /OIDC_PROVIDER_RUNTIME_NOT_PUBLIC/);
    assert.equal(calls.length, 1);
    assert.match(calls[0].url, /\.well-known\/openid-configuration$/);
    assert.equal(new Headers(calls[0].init.headers).has("authorization"), false);
    assert.equal(new Headers(calls[0].init.headers).has("cookie"), false);

    assert.equal(OIDC_RUNTIME_IMPLEMENTED, false);
    assert.deepEqual(oidcRuntimeDiscoveryPolicy, {
      discoveryOnly: true,
      loginRoutesImplemented: false,
      callbackRoutesImplemented: false,
      tokenExchangeImplemented: false,
      sessionsImplemented: false,
      tenantMembershipImplemented: false,
      supportedClientAuthenticationMethod: "CLIENT_SECRET_BASIC",
      supportedScopes: ["openid", "profile", "email"],
      allowedIdTokenSigningAlgorithms: ["RS256", "PS256", "ES256"]
    });
  });

  test("C1B runtime fails closed on unsupported or unsafe provider metadata", async () => {
    const cases = [
      [{ authorization_endpoint: "http://idp.example.test/authorize" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ token_endpoint: "https://user@idp.example.test/token" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ jwks_uri: "https://idp.example.test/keys?tenant=a" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ response_types_supported: ["id_token"] }, "OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED"],
      [{ grant_types_supported: ["client_credentials"] }, "OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED"],
      [{ code_challenge_methods_supported: ["plain"] }, "OIDC_PROVIDER_PKCE_S256_UNSUPPORTED"],
      [{ token_endpoint_auth_methods_supported: ["client_secret_post"] }, "OIDC_PROVIDER_CLIENT_AUTH_UNSUPPORTED"],
      [{ id_token_signing_alg_values_supported: ["HS256"] }, "OIDC_PROVIDER_ID_TOKEN_ALG_UNSUPPORTED"],
      [{ scopes_supported: ["openid"] }, "OIDC_PROVIDER_METADATA_INVALID"]
    ];

    for (const [override, code] of cases) {
      const result = await loadOidcProviderRuntime(validConfiguration(), {
        fetch: metadataFetch(providerMetadata(override)),
        cache: createOidcRuntimeCache()
      });
      expectError(result, code);
    }
  });

  test("C1B runtime maps discovery network bounds to safe errors", async () => {
    const configuration = validConfiguration();
    expectError(await loadOidcProviderRuntime(configuration, {
      fetch: async () => new Response(null, { status: 302, headers: { location: "https://idp.example.test/next" } }),
      cache: createOidcRuntimeCache()
    }), "OIDC_DISCOVERY_REDIRECT_REJECTED");

    expectError(await loadOidcProviderRuntime(configuration, {
      fetch: async () => jsonResponse(providerMetadata(), { contentLength: "9999" }),
      maxBodyBytes: 32,
      cache: createOidcRuntimeCache()
    }), "OIDC_DISCOVERY_RESPONSE_TOO_LARGE");

    expectError(await loadOidcProviderRuntime(configuration, {
      fetch: async () => jsonResponse(providerMetadata(), { contentType: "text/html" }),
      cache: createOidcRuntimeCache()
    }), "OIDC_DISCOVERY_CONTENT_TYPE_INVALID");

    expectError(await loadOidcProviderRuntime(configuration, {
      fetch: async (_url, init = {}) => new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      }),
      timeoutMs: 5,
      cache: createOidcRuntimeCache()
    }), "OIDC_DISCOVERY_TIMEOUT");

    expectError(await loadOidcProviderRuntime(configuration, {
      fetch: async () => jsonResponse("{"),
      cache: createOidcRuntimeCache()
    }), "OIDC_PROVIDER_DISCOVERY_FAILED");
  });

  test("C1B runtime caches successful issuer metadata by issuer and never serves stale failures", async () => {
    let now = 1_000;
    const calls = [];
    const cache = createOidcRuntimeCache();
    const configuration = validConfiguration();
    const first = await loadOidcProviderRuntime(configuration, {
      fetch: metadataFetch(providerMetadata(), calls),
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(first.ok, true);

    const second = await loadOidcProviderRuntime(validConfiguration({ ORDERPILOT_OIDC_CLIENT_SECRET: "different-valid-secret-0123456789" }), {
      fetch: async () => {
        throw new Error("cache miss should not happen");
      },
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(second.ok, true);
    assert.equal(calls.length, 1);

    now = 1_011;
    const expired = await loadOidcProviderRuntime(configuration, {
      fetch: metadataFetch(providerMetadata({ scopes_supported: ["openid"] }), calls),
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    expectError(expired, "OIDC_PROVIDER_METADATA_INVALID");
    assert.equal(calls.length, 2);
  });

  test("C1B runtime coalesces concurrent discovery for the same issuer", async () => {
    const cache = createOidcRuntimeCache();
    const calls = [];
    let release;
    const fetch = async (url, init = {}) => {
      calls.push({ url, init });
      return new Promise((resolve) => {
        release = () => resolve(jsonResponse(providerMetadata()));
      });
    };

    const first = loadOidcProviderRuntime(validConfiguration(), { fetch, cache });
    const second = loadOidcProviderRuntime(validConfiguration(), { fetch, cache });
    assert.equal(calls.length, 1);
    release();
    const results = await Promise.all([first, second]);
    assert.equal(results[0].ok, true);
    assert.equal(results[1].ok, true);
    assert.equal(calls.length, 1);
  });
}