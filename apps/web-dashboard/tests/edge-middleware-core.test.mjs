import assert from "node:assert/strict";
import test from "node:test";
import {
  MIDDLEWARE_SECURITY_HEADERS,
  decideEdgeMiddleware,
  isPublicMiddlewarePath
} from "../lib/edge-middleware-core.ts";

const REQUIRED_HEADERS = [
  "X-Content-Type-Options",
  "X-Frame-Options",
  "Referrer-Policy",
  "Permissions-Policy",
  "Cache-Control"
];

test("F09: the security-header set is complete and hardened", () => {
  for (const header of REQUIRED_HEADERS) {
    assert.ok(MIDDLEWARE_SECURITY_HEADERS[header], `${header} present`);
  }
  assert.equal(MIDDLEWARE_SECURITY_HEADERS["X-Content-Type-Options"], "nosniff");
  assert.equal(MIDDLEWARE_SECURITY_HEADERS["X-Frame-Options"], "DENY");
  assert.equal(MIDDLEWARE_SECURITY_HEADERS["Cache-Control"], "no-store");
});

test("F09: every branch is defined so headers apply uniformly (pass-through / 401 / redirect)", () => {
  // pass-through cases
  assert.deepEqual(
    decideEdgeMiddleware({ pathname: "/order-journey", search: "", enabled: false, hasSessionCookie: false }),
    { kind: "next" }
  );
  assert.deepEqual(
    decideEdgeMiddleware({ pathname: "/login", search: "", enabled: true, hasSessionCookie: false }),
    { kind: "next" }
  );
  assert.deepEqual(
    decideEdgeMiddleware({ pathname: "/order-journey", search: "", enabled: true, hasSessionCookie: true }),
    { kind: "next" }
  );
  // protected API without a session cookie -> bounded JSON 401 (never a redirect)
  assert.deepEqual(
    decideEdgeMiddleware({ pathname: "/api/bff/api/v1/quote-review/queue", search: "", enabled: true, hasSessionCookie: false }),
    { kind: "json-401" }
  );
});

test("F09: protected page redirect preserves safe internal path + query", () => {
  const decision = decideEdgeMiddleware({
    pathname: "/workspace/draft-quotes",
    search: "?status=OPEN",
    enabled: true,
    hasSessionCookie: false
  });
  assert.equal(decision.kind, "redirect");
  assert.equal(
    decision.location,
    `/login?next=${encodeURIComponent("/workspace/draft-quotes?status=OPEN")}`
  );
});

test("F09: redirect strips external/encoded/backslash destinations to /", () => {
  const cases = [
    { pathname: "//attacker.example", search: "" },
    { pathname: "/dashboard", search: "?redirect=//evil.example" }, // '//' anywhere collapses to /
    { pathname: "/\\attacker.example", search: "" },
    { pathname: "/%2F%2Fattacker.example", search: "" }
  ];
  for (const { pathname, search } of cases) {
    const decision = decideEdgeMiddleware({ pathname, search, enabled: true, hasSessionCookie: false });
    assert.equal(decision.kind, "redirect");
    assert.equal(decision.location, `/login?next=${encodeURIComponent("/")}`, `${pathname}${search}`);
    assert.doesNotMatch(decision.location, /attacker\.example|evil\.example/);
  }
});

test("F09: public/static path classification", () => {
  assert.equal(isPublicMiddlewarePath("/login"), true);
  assert.equal(isPublicMiddlewarePath("/api/auth/session"), true);
  assert.equal(isPublicMiddlewarePath("/_next/static/x.js"), true);
  assert.equal(isPublicMiddlewarePath("/favicon.ico"), true);
  assert.equal(isPublicMiddlewarePath("/order-journey"), false);
  assert.equal(isPublicMiddlewarePath("/api/bff/api/v1/quotes"), false);
});
