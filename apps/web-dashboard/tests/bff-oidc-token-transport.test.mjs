import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD = "ORDERPILOT_OIDC_TOKEN_TRANSPORT_CHILD";
if (process.env[CHILD] !== "1") {
  test("OIDC token transport tests run under server-only condition", () => {
    const result = spawnSync(
      process.execPath,
      ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)],
      { cwd: process.cwd(), env: { ...process.env, [CHILD]: "1" }, encoding: "utf8" }
    );
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const { createOidcTokenRuntimeFetch } = await import("../lib/bff/bff-oidc-token-transport.ts");
  const runtime = {
    tokenEndpoint: "https://idp.example.test/token",
    jwksUri: "https://idp.example.test/jwks"
  };

  test("token and JWKS requests have a hard timeout", async () => {
    const bounded = createOidcTokenRuntimeFetch(runtime, async (_url, init = {}) =>
      new Promise((_resolve, reject) => init.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true })),
    { timeoutMs: 5 });
    await assert.rejects(() => bounded(runtime.tokenEndpoint, { method: "POST" }), /OIDC_RUNTIME_TIMEOUT/);
  });

  test("oversized error responses and redirects fail before publication", async () => {
    const oversized = createOidcTokenRuntimeFetch(runtime, async () =>
      new Response("{}", { status: 400, headers: { "content-type": "application/json", "content-length": "999" } }),
    { maxBodyBytes: 32 });
    await assert.rejects(() => oversized(runtime.tokenEndpoint, { method: "POST" }), /OIDC_RUNTIME_RESPONSE_TOO_LARGE/);

    const redirected = createOidcTokenRuntimeFetch(runtime, async () =>
      new Response(null, { status: 302, headers: { location: "https://idp.example.test/next" } }));
    await assert.rejects(() => redirected(runtime.jwksUri), /OIDC_RUNTIME_REDIRECT_DENIED/);
  });

  test("only exact token and JWKS endpoints are allowed", async () => {
    const bounded = createOidcTokenRuntimeFetch(runtime, async () => Response.json({ ok: true }));
    const response = await bounded(runtime.jwksUri);
    assert.equal(response.status, 200);
    await assert.rejects(() => bounded("https://idp.example.test/admin"), /OIDC_RUNTIME_URL_DENIED/);
  });
}
