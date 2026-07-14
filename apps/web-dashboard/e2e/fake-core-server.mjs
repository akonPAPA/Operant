/**
 * Bounded fake Core API for P1-B browser E2E. Records every request (method, path,
 * selected headers) so tests can prove exactly which requests crossed the BFF boundary.
 * Never mutates anything; JSON-only; test-only.
 */
import { createServer } from "node:http";

const PORT = Number.parseInt(process.env.FAKE_CORE_PORT ?? "18080", 10);

/** @type {{method: string, path: string, headers: Record<string, string>}[]} */
let recorded = [];

function pickHeaders(req) {
  const interesting = [
    "x-tenant-id",
    "x-orderpilot-actor-id",
    "x-orderpilot-permissions",
    "x-orderpilot-gateway-signature",
    "x-orderpilot-gateway-nonce",
    "x-orderpilot-gateway-timestamp",
    "x-orderpilot-staff-grant",
    "authorization",
    "cookie",
    "idempotency-key",
    "content-type"
  ];
  const headers = {};
  for (const name of interesting) {
    if (req.headers[name] !== undefined) {
      headers[name] = String(req.headers[name]);
    }
  }
  return headers;
}

function json(res, status, body, extraHeaders = {}) {
  res.writeHead(status, { "Content-Type": "application/json", ...extraHeaders });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  const path = new URL(req.url, `http://127.0.0.1:${PORT}`).pathname;

  if (path === "/__test/requests" && req.method === "GET") {
    return json(res, 200, recorded);
  }
  if (path === "/__test/requests" && req.method === "DELETE") {
    recorded = [];
    return json(res, 200, { cleared: true });
  }

  recorded.push({ method: req.method, path, headers: pickHeaders(req) });

  if (path === "/api/v1/quote-review/queue" && req.method === "GET") {
    return json(res, 200, { items: [], totalCount: 0 });
  }
  if (/^\/api\/v1\/quote-review\/[^/]+\/assemble-draft$/.test(path) && req.method === "POST") {
    return json(res, 200, { ok: true, draftStatus: "DRAFT_ASSEMBLED" });
  }
  if (path === "/api/v1/analytics/overview" && req.method === "GET") {
    // deliberately hostile response: raw stack trace + internal headers + Set-Cookie —
    // none of it may reach the browser through the BFF
    res.writeHead(500, {
      "Content-Type": "text/plain",
      "Set-Cookie": "core_internal_session=leak; Path=/",
      "X-Internal-Trace": "core-trace-secret",
      "X-Powered-By": "orderpilot-core"
    });
    return res.end("java.lang.NullPointerException\n\tat com.orderpilot.internal.SecretService");
  }
  if (path.startsWith("/api/v1/internal/")) {
    return json(res, 200, { leaked: "this response must never be reachable via BFF" });
  }
  return json(res, 404, { message: "not found (fake core)" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`fake core listening on http://127.0.0.1:${PORT}`);
});
