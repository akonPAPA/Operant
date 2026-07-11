import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { readBrowserCsrfCookieFromDocument } from "../lib/browser-csrf-cookie.ts";

describe("browser CSRF cookie parser", () => {
  it("rejects malformed percent encoding without throwing", () => {
    assert.deepEqual(readBrowserCsrfCookieFromDocument("op_csrf=%"), { ok: false, reason: "malformed" });
    assert.deepEqual(readBrowserCsrfCookieFromDocument("op_csrf=%ZZ"), { ok: false, reason: "malformed" });
  });

  it("fails closed on duplicate cookies", () => {
    const token = "csrf-token-0123456789abcdef0123456789ab";
    const header = `op_csrf=${token}; op_csrf=${token}`;
    assert.deepEqual(readBrowserCsrfCookieFromDocument(header), { ok: false, reason: "duplicate" });
  });

  it("accepts a valid token", () => {
    const token = "csrf-token-0123456789abcdef0123456789ab";
    assert.deepEqual(readBrowserCsrfCookieFromDocument(`op_csrf=${encodeURIComponent(token)}`), {
      ok: true,
      token
    });
  });
});
