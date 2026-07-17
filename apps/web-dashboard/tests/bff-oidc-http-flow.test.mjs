import assert from "node:assert/strict";
import test from "node:test";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const CHILD_FLAG = "ORDERPILOT_OIDC_HTTP_FLOW_CHILD";

if (process.env[CHILD_FLAG] !== "1") {
  test("P1-C HTTP OIDC flow passes under server-only condition", () => {
    const result = spawnSync(process.execPath, ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)], {
      cwd: process.cwd(),
      env: { ...process.env, [CHILD_FLAG]: "1" },
      encoding: "utf8"
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const { createHash, createSign, generateKeyPairSync } = await import("node:crypto");
  const { createServer } = await import("node:http");
  const { once } = await import("node:events");
  const { handleOidcCallback, handleOidcLogin, handleLogout } = await import("../lib/bff/bff-auth-handlers.ts");
  const { proxyCoreRequest } = await import("../lib/bff/bff-proxy.ts");
  const { resetSessionStoreForTesting } = await import("../lib/bff/bff-session-store.ts");
  const { resetOidcTransactionStoreForTesting } = await import("../lib/bff/bff-oidc-transaction-store.ts");

  const ENV_KEYS = [
    "NODE_ENV", "ORDERPILOT_DEPLOY_PROFILE", "ORDERPILOT_BFF_ENABLED", "ORDERPILOT_DEMO_MODE", "ORDERPILOT_BFF_SESSION_STORE",
    "ORDERPILOT_PUBLIC_ORIGIN", "CORE_API_BASE_URL", "ORDERPILOT_GATEWAY_SHARED_SECRET", "ORDERPILOT_OIDC_ENABLED",
    "ORDERPILOT_OIDC_ISSUER", "ORDERPILOT_OIDC_CLIENT_ID", "ORDERPILOT_OIDC_CLIENT_SECRET", "ORDERPILOT_OIDC_REDIRECT_URI",
    "ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI", "ORDERPILOT_OIDC_SCOPES", "ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD",
    "ORDERPILOT_OIDC_IDENTITY_MAPPINGS_JSON"
  ];
  const CLIENT_ID = "operant-dashboard-client";
  const CLIENT_SECRET = "local-provider-secret-0123456789abcdef";
  const TENANT_ID = "11111111-1111-4111-8111-111111111111";
  const ACTOR_ID = "22222222-2222-4222-8222-222222222222";
  const SUBJECT = "oidc-sub-tenant-user-1";
  const GATEWAY_SECRET = "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";

  async function listen(server) {
    server.listen(0, "127.0.0.1");
    await once(server, "listening");
    return server.address().port;
  }

  function closeServer(server) {
    return new Promise((resolve) => server.close(resolve));
  }

  function b64url(input) {
    return Buffer.from(input).toString("base64url");
  }

  function pkceChallenge(verifier) {
    return createHash("sha256").update(verifier).digest("base64url");
  }

  function writeNodeResponse(nodeResponse, webResponse) {
    nodeResponse.statusCode = webResponse.status;
    const setCookies = typeof webResponse.headers.getSetCookie === "function" ? webResponse.headers.getSetCookie() : [];
    for (const [name, value] of webResponse.headers) {
      if (name.toLowerCase() !== "set-cookie") nodeResponse.setHeader(name, value);
    }
    if (setCookies.length > 0) nodeResponse.setHeader("Set-Cookie", setCookies);
    return webResponse.arrayBuffer().then((body) => nodeResponse.end(Buffer.from(body)));
  }

  function requestFromIncoming(req, port) {
    return new Request(`http://127.0.0.1:${port}${req.url}`, { method: req.method, headers: req.headers });
  }

  function startProvider() {
    const codes = new Map();
    const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const publicJwk = publicKey.export({ format: "jwk" });
    const kid = "local-test-key-1";
    Object.assign(publicJwk, { kid, alg: "RS256", use: "sig" });
    let issuer;
    function idToken(nonce) {
      const now = Math.floor(Date.now() / 1000);
      const header = b64url(JSON.stringify({ alg: "RS256", kid, typ: "JWT" }));
      const payload = b64url(JSON.stringify({
        iss: issuer,
        sub: SUBJECT,
        aud: CLIENT_ID,
        exp: now + 600,
        iat: now,
        nbf: now - 5,
        nonce,
        email: "operator@example.test",
        email_verified: true
      }));
      const signer = createSign("RSA-SHA256");
      signer.update(`${header}.${payload}`);
      signer.end();
      return `${header}.${payload}.${signer.sign(privateKey).toString("base64url")}`;
    }
    const server = createServer((req, res) => {
      const url = new URL(req.url, issuer);
      if (url.pathname.endsWith("/.well-known/openid-configuration")) {
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({
          issuer,
          authorization_endpoint: `${issuer}/authorize`,
          token_endpoint: `${issuer}/token`,
          jwks_uri: `${issuer}/jwks`,
          response_types_supported: ["code"],
          grant_types_supported: ["authorization_code"],
          code_challenge_methods_supported: ["S256"],
          token_endpoint_auth_methods_supported: ["client_secret_basic"],
          scopes_supported: ["openid", "profile", "email"],
          id_token_signing_alg_values_supported: ["RS256"]
        }));
        return;
      }
      if (url.pathname === "/provider/authorize") {
        assert.equal(url.searchParams.get("client_id"), CLIENT_ID);
        assert.equal(url.searchParams.get("response_type"), "code");
        assert.equal(url.searchParams.get("code_challenge_method"), "S256");
        const code = `code-${codes.size + 1}`;
        codes.set(code, {
          redirectUri: url.searchParams.get("redirect_uri"),
          codeChallenge: url.searchParams.get("code_challenge"),
          nonce: url.searchParams.get("nonce")
        });
        const redirect = new URL(url.searchParams.get("redirect_uri"));
        redirect.searchParams.set("code", code);
        redirect.searchParams.set("state", url.searchParams.get("state"));
        res.statusCode = 302;
        res.setHeader("Location", redirect.href);
        res.end();
        return;
      }
      if (url.pathname === "/provider/jwks") {
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ keys: [publicJwk] }));
        return;
      }
      if (url.pathname === "/provider/token") {
        let body = "";
        req.on("data", (chunk) => { body += chunk; });
        req.on("end", () => {
          const basic = Buffer.from((req.headers.authorization ?? "").replace(/^Basic\s+/i, ""), "base64").toString("utf8");
          assert.equal(basic, `${CLIENT_ID}:${CLIENT_SECRET}`);
          const form = new URLSearchParams(body);
          const code = form.get("code");
          const record = codes.get(code);
          codes.delete(code);
          if (!record || form.get("grant_type") !== "authorization_code" || form.get("redirect_uri") !== record.redirectUri || pkceChallenge(form.get("code_verifier") ?? "") !== record.codeChallenge) {
            res.statusCode = 400;
            res.setHeader("Content-Type", "application/json");
            res.end(JSON.stringify({ error: "invalid_grant" }));
            return;
          }
          res.setHeader("Content-Type", "application/json");
          res.end(JSON.stringify({ access_token: "opaque-access-token", token_type: "Bearer", expires_in: 300, id_token: idToken(record.nonce) }));
        });
        return;
      }
      res.statusCode = 404;
      res.end();
    });
    return { server, setIssuer: (value) => { issuer = value; } };
  }

  function startCore(calls) {
    return createServer((req, res) => {
      calls.push({ url: req.url, headers: req.headers });
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ ok: true }));
    });
  }

  function startBff(portBox) {
    const server = createServer(async (req, res) => {
      try {
        const request = requestFromIncoming(req, portBox.port);
        const url = new URL(request.url);
        if (url.pathname === "/api/auth/oidc/login") return writeNodeResponse(res, await handleOidcLogin(request));
        if (url.pathname === "/api/auth/oidc/callback") return writeNodeResponse(res, await handleOidcCallback(request));
        if (url.pathname === "/api/auth/logout") return writeNodeResponse(res, await handleLogout(request));
        if (url.pathname.startsWith("/api/bff/")) return writeNodeResponse(res, await proxyCoreRequest(request, url.pathname.replace(/^\/api\/bff\//, "").split("/")));
        res.statusCode = 404;
        res.end();
      } catch (error) {
        res.statusCode = 500;
        res.end(String(error?.message ?? error));
      }
    });
    return server;
  }

  async function withEnv(vars, fn) {
    const prior = {};
    for (const key of ENV_KEYS) { prior[key] = process.env[key]; delete process.env[key]; }
    Object.assign(process.env, vars);
    resetSessionStoreForTesting();
    resetOidcTransactionStoreForTesting();
    try { return await fn(); } finally {
      for (const key of ENV_KEYS) {
        if (prior[key] === undefined) delete process.env[key]; else process.env[key] = prior[key];
      }
      resetSessionStoreForTesting();
      resetOidcTransactionStoreForTesting();
    }
  }

  function cookieJarFrom(response) {
    return response.headers.getSetCookie().map((cookie) => cookie.split(";")[0]).join("; ");
  }

  test("HTTP-level login callback BFF Core logout flow uses server-resolved tenant authority", async () => {
    const provider = startProvider();
    const providerPort = await listen(provider.server);
    const issuer = `http://127.0.0.1:${providerPort}/provider`;
    provider.setIssuer(issuer);
    const coreCalls = [];
    const core = startCore(coreCalls);
    const corePort = await listen(core);
    const portBox = { port: 0 };
    const bff = startBff(portBox);
    portBox.port = await listen(bff);
    const publicOrigin = `http://127.0.0.1:${portBox.port}`;
    const mapping = { mappings: [{
      issuer,
      subject: SUBJECT,
      audience: CLIENT_ID,
      enabled: true,
      accessPlane: "TENANT_USER",
      tenantRef: TENANT_ID,
      actorRef: ACTOR_ID,
      bffPermissions: ["REVIEW_READ"],
      safeEmail: "operator@example.test",
      requireEmail: "operator@example.test",
      mappingVersion: "http-flow-v1"
    }] };
    try {
      await withEnv({
        NODE_ENV: "test",
        ORDERPILOT_DEPLOY_PROFILE: "local-test",
        ORDERPILOT_BFF_ENABLED: "true",
        ORDERPILOT_DEMO_MODE: "false",
        ORDERPILOT_BFF_SESSION_STORE: "memory",
        ORDERPILOT_PUBLIC_ORIGIN: publicOrigin,
        CORE_API_BASE_URL: `http://127.0.0.1:${corePort}`,
        ORDERPILOT_GATEWAY_SHARED_SECRET: GATEWAY_SECRET,
        ORDERPILOT_OIDC_ENABLED: "true",
        ORDERPILOT_OIDC_ISSUER: issuer,
        ORDERPILOT_OIDC_CLIENT_ID: CLIENT_ID,
        ORDERPILOT_OIDC_CLIENT_SECRET: CLIENT_SECRET,
        ORDERPILOT_OIDC_REDIRECT_URI: `${publicOrigin}/api/auth/oidc/callback`,
        ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${publicOrigin}/login`,
        ORDERPILOT_OIDC_SCOPES: "openid profile email",
        ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "CLIENT_SECRET_BASIC",
        ORDERPILOT_OIDC_IDENTITY_MAPPINGS_JSON: JSON.stringify(mapping)
      }, async () => {
        const login = await fetch(`${publicOrigin}/api/auth/oidc/login`, { redirect: "manual" });
        assert.equal(login.status, 302);
        const authorization = await fetch(login.headers.get("location"), { redirect: "manual" });
        assert.equal(authorization.status, 302);
        const callback = await fetch(authorization.headers.get("location"), { redirect: "manual" });
        assert.equal(callback.status, 302);
        const cookie = cookieJarFrom(callback);
        assert.match(cookie, /op_session=/);
        assert.match(cookie, /op_csrf=/);
        const csrf = /op_csrf=([^;]+)/.exec(cookie)[1];

        const proxied = await fetch(`${publicOrigin}/api/bff/api/v1/quote-review/queue`, { headers: { cookie }, redirect: "manual" });
        assert.equal(proxied.status, 200);
        assert.equal(coreCalls.length, 1);
        assert.equal(coreCalls[0].headers["x-tenant-id"], TENANT_ID);
        assert.equal(coreCalls[0].headers["x-orderpilot-actor-id"], ACTOR_ID);
        assert.equal(coreCalls[0].headers["x-orderpilot-permissions"], "REVIEW_READ");
        assert.equal(coreCalls[0].headers.cookie, undefined);

        const logout = await fetch(`${publicOrigin}/api/auth/logout`, { method: "POST", headers: { cookie, origin: publicOrigin, "X-OP-CSRF-Token": csrf }, redirect: "manual" });
        assert.equal(logout.status, 200);
        assert.ok(logout.headers.getSetCookie().some((value) => value.startsWith("op_session=;")));
        const denied = await fetch(`${publicOrigin}/api/bff/api/v1/quote-review/queue`, { headers: { cookie }, redirect: "manual" });
        assert.equal(denied.status, 401);
        assert.equal(coreCalls.length, 1, "revoked session cannot reach Core");
      });
    } finally {
      await closeServer(bff);
      await closeServer(core);
      await closeServer(provider.server);
    }
  });
}
