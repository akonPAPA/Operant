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
    loadValidatedOidcConfiguration,
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
    const result = loadValidatedOidcConfiguration(validEnv(overrides));
    assert.equal(result.ok, true, "expected valid OIDC configuration");
    return result.configuration;
  }

  function providerMetadata(overrides = {}) {
    const issuer = overrides.issuer ?? VALID_ISSUER;
    return {
      issuer,
      authorization_endpoint: `${issuer}/oauth2/v1/authorize`,
      token_endpoint: `${issuer}/oauth2/v1/token`,
      jwks_uri: `${issuer}/oauth2/v1/keys`,
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

  test("C1B runtime accepts only factory-created validated configuration", async () => {
    const disabledStatus = readOidcConfigurationStatus({});
    const calls = [];
    expectError(await loadOidcProviderRuntime(disabledStatus, { fetch: metadataFetch(providerMetadata(), calls) }), "OIDC_CONFIGURATION_INVALID");
    assert.equal(calls.length, 0);

    const realConfiguration = validConfiguration();
    const copiedConfiguration = { ...realConfiguration };
    const assignedConfiguration = Object.assign({}, realConfiguration);
    const jsonCopiedConfiguration = JSON.parse(JSON.stringify(copiedConfiguration));
    const symbolCopiedConfiguration = { ...copiedConfiguration, [Symbol("ValidOidcConfiguration")]: true };
    const manualBrandRecreation = { ...copiedConfiguration, __validOidcConfigurationBrand: true };
    const frozenCopy = Object.freeze({ ...realConfiguration });
    const sealedCopy = Object.seal({ ...realConfiguration });
    const prototypeCopy = Object.setPrototypeOf({ ...realConfiguration }, realConfiguration);
    const objectCreated = Object.create(realConfiguration);
    const proxyWrapped = new Proxy(realConfiguration, {});
    const descriptorCopy = Object.defineProperties({}, Object.getOwnPropertyDescriptors(realConfiguration));
    const forgedStatusConfiguration = {
      state: "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED",
      enabled: true,
      runtimeImplemented: false,
      configuration: copiedConfiguration
    };

    for (const forged of [
      copiedConfiguration,
      assignedConfiguration,
      jsonCopiedConfiguration,
      symbolCopiedConfiguration,
      manualBrandRecreation,
      frozenCopy,
      sealedCopy,
      prototypeCopy,
      objectCreated,
      proxyWrapped,
      descriptorCopy,
      forgedStatusConfiguration
    ]) {
      expectError(await loadOidcProviderRuntime(forged, { fetch: metadataFetch(providerMetadata(), calls) }), "OIDC_CONFIGURATION_INVALID");
    }
    assert.equal(calls.length, 0);

    const accepted = await loadOidcProviderRuntime(realConfiguration, {
      fetch: metadataFetch(providerMetadata(), calls),
      cache: createOidcRuntimeCache()
    });
    assert.equal(accepted.ok, true);
    assert.equal(calls.length, 1);
    assert.throws(() => JSON.stringify(realConfiguration), /OIDC_CONFIGURATION_NOT_PUBLIC/);
    assert.equal(validOidcConfiguration(validEnv()).issuer, VALID_ISSUER);
  });

  test("C1B runtime requires an injected controlled egress transport", async () => {
    let globalFetchCalls = 0;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
      globalFetchCalls += 1;
      throw new Error("global fetch must not be used for OIDC discovery");
    };
    try {
      const result = await loadOidcProviderRuntime(validConfiguration(), { cache: createOidcRuntimeCache() });
      expectError(result, "OIDC_CONTROLLED_EGRESS_REQUIRED");
      assert.equal(globalFetchCalls, 0);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test("C1B runtime validates provider metadata with login callback tokens and sessions enabled", async () => {
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

    assert.equal(OIDC_RUNTIME_IMPLEMENTED, true);
    assert.deepEqual(oidcRuntimeDiscoveryPolicy, {
      discoveryOnly: false,
      productionDiscoveryRequiresControlledEgress: true,
      loginRoutesImplemented: true,
      callbackRoutesImplemented: true,
      tokenExchangeImplemented: true,
      sessionsImplemented: true,
      tenantMembershipImplemented: true,
      supportedClientAuthenticationMethod: "CLIENT_SECRET_BASIC",
      supportedScopes: ["openid", "profile", "email"],
      allowedIdTokenSigningAlgorithms: ["RS256", "PS256", "ES256"]
    });
  });

  test("C1B runtime fails closed on unsupported or unsafe provider metadata", async () => {
    const cases = [
      [{ authorization_endpoint: "http://idp.example.test/authorize" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ authorization_endpoint: "https://evil.example.test/authorize" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ token_endpoint: "https://user@idp.example.test/token" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
      [{ token_endpoint: "https://127.0.0.1/token" }, "OIDC_PROVIDER_ENDPOINT_UNSAFE"],
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

  test("C1B runtime caches immutable raw issuer metadata and revalidates each current policy", async () => {
    let now = 1_000;
    const calls = [];
    const cache = createOidcRuntimeCache();
    const narrow = validConfiguration({ ORDERPILOT_OIDC_SCOPES: "openid profile" });
    const first = await loadOidcProviderRuntime(narrow, {
      fetch: metadataFetch(providerMetadata({ scopes_supported: ["openid", "profile"] }), calls),
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(first.ok, true);
    const entry = cache.entries.get(VALID_ISSUER);
    assert.ok(entry);
    assert.equal(Object.isFrozen(entry.metadata), true);
    assert.equal(Object.isFrozen(entry.metadata.scopes_supported), true);
    assert.throws(() => entry.metadata.scopes_supported.push("email"), /Cannot/);
    assert.throws(() => { entry.metadata.issuer = "https://evil.example.test"; }, /Cannot/);
    assert.throws(() => first.runtime.supportedScopes.push("email"), /Cannot/);
    assert.throws(() => { first.runtime.issuer = "https://evil.example.test"; }, /Cannot/);

    const differentSecret = await loadOidcProviderRuntime(validConfiguration({
      ORDERPILOT_OIDC_SCOPES: "openid profile",
      ORDERPILOT_OIDC_CLIENT_SECRET: "different-valid-secret-0123456789"
    }), {
      fetch: async () => {
        throw new Error("cache miss should not happen for metadata-compatible secret rotation");
      },
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(differentSecret.ok, true);

    const broaderUnsupportedScopes = await loadOidcProviderRuntime(validConfiguration(), {
      fetch: async () => {
        throw new Error("validated-result reuse would hide unsupported scopes");
      },
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    expectError(broaderUnsupportedScopes, "OIDC_PROVIDER_METADATA_INVALID");
    assert.equal(calls.length, 1);

    const differentClient = await loadOidcProviderRuntime(validConfiguration({
      ORDERPILOT_OIDC_SCOPES: "openid profile",
      ORDERPILOT_OIDC_CLIENT_ID: "operant-dashboard-client-b"
    }), {
      fetch: async () => {
        throw new Error("metadata cache hit should not refetch for client id change");
      },
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(differentClient.ok, true);
    assert.notEqual(differentClient.runtime, first.runtime);

    const differentRedirect = await loadOidcProviderRuntime(validConfiguration({
      ORDERPILOT_OIDC_SCOPES: "openid profile",
      ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard-b.example.test",
      ORDERPILOT_OIDC_REDIRECT_URI: "https://dashboard-b.example.test/api/auth/oidc/callback",
      ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: "https://dashboard-b.example.test/login"
    }), {
      fetch: async () => {
        throw new Error("metadata cache hit should not refetch for redirect policy change");
      },
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    assert.equal(differentRedirect.ok, true);
    assert.notEqual(differentRedirect.runtime, first.runtime);

    now = 1_011;
    const expired = await loadOidcProviderRuntime(narrow, {
      fetch: metadataFetch(providerMetadata({ scopes_supported: ["openid"] }), calls),
      cache,
      now: () => now,
      cacheTtlMs: 10
    });
    expectError(expired, "OIDC_PROVIDER_METADATA_INVALID");
    assert.equal(calls.length, 2);
  });

  test("C1B runtime coalesces inflight metadata without sharing incompatible final validation", async () => {
    const cache = createOidcRuntimeCache();
    const calls = [];
    let release;
    const fetch = async (url, init = {}) => {
      calls.push({ url, init });
      return new Promise((resolve) => {
        release = () => resolve(jsonResponse(providerMetadata({ scopes_supported: ["openid", "profile"] })));
      });
    };

    const first = loadOidcProviderRuntime(validConfiguration({ ORDERPILOT_OIDC_SCOPES: "openid profile" }), { fetch, cache });
    const second = loadOidcProviderRuntime(validConfiguration(), { fetch, cache });
    assert.equal(calls.length, 1);
    release();
    const results = await Promise.all([first, second]);
    assert.equal(results[0].ok, true);
    expectError(results[1], "OIDC_PROVIDER_METADATA_INVALID");
    assert.equal(calls.length, 1);
  });

  test("C1B runtime keeps cache bounded and deterministic", async () => {
    const cache = createOidcRuntimeCache();
    const issuers = Array.from({ length: 66 }, (_value, index) => `https://idp-${index}.example.test/tenant`);
    for (const issuer of issuers) {
      const configuration = validConfiguration({ ORDERPILOT_OIDC_ISSUER: issuer });
      const result = await loadOidcProviderRuntime(configuration, {
        fetch: metadataFetch(providerMetadata({ issuer })),
        cache,
        maxCacheEntries: 9999,
        cacheTtlMs: 99_999_999,
        now: () => 5_000
      });
      assert.equal(result.ok, true);
      assert.ok(result.runtime.expiresAtEpochMs <= 5_000 + 15 * 60 * 1000);
    }
    assert.equal(cache.entries.size, 64);
    assert.deepEqual([...cache.entries.keys()].slice(0, 2), [issuers[2], issuers[3]]);
  });

  test("C1B runtime does not cache failures as success and stale inflight cannot overwrite newer cache", async () => {
    const cache = createOidcRuntimeCache();
    const configuration = validConfiguration({ ORDERPILOT_OIDC_SCOPES: "openid profile" });
    const failed = await loadOidcProviderRuntime(configuration, {
      fetch: metadataFetch(providerMetadata({ scopes_supported: ["openid"] })),
      cache,
      now: () => 1_000,
      cacheTtlMs: 10
    });
    expectError(failed, "OIDC_PROVIDER_METADATA_INVALID");
    assert.equal(cache.entries.size, 0, "invalid metadata is not cached as validated provider metadata");

    let release;
    const staleFetch = async () => new Promise((resolve) => {
      release = () => resolve(jsonResponse(providerMetadata({ scopes_supported: ["openid", "profile"] })));
    });
    const staleWork = loadOidcProviderRuntime(configuration, {
      fetch: staleFetch,
      cache,
      now: () => 2_000,
      cacheTtlMs: 10
    });
    cache.entries.set(VALID_ISSUER, {
      metadata: Object.freeze({
        issuer: VALID_ISSUER,
        authorization_endpoint: `${VALID_ISSUER}/oauth2/v1/authorize`,
        token_endpoint: `${VALID_ISSUER}/oauth2/v1/token`,
        jwks_uri: `${VALID_ISSUER}/oauth2/v1/keys`,
        response_types_supported: Object.freeze(["code"]),
        grant_types_supported: Object.freeze(["authorization_code"]),
        code_challenge_methods_supported: Object.freeze(["S256"]),
        token_endpoint_auth_methods_supported: Object.freeze(["client_secret_basic"]),
        scopes_supported: Object.freeze(["openid"]),
        id_token_signing_alg_values_supported: Object.freeze(["RS256"])
      }),
      expiresAtEpochMs: 5_000
    });
    release();
    const staleResult = await staleWork;
    expectError(staleResult, "OIDC_PROVIDER_METADATA_INVALID");
  });
}
