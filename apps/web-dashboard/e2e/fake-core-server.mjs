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

const DEMO_QUOTE_ID = "44444444-4444-4444-8444-444444444444";

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      try {
        const text = Buffer.concat(chunks).toString("utf8");
        resolve(text ? JSON.parse(text) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function sampleQuoteTransaction() {
  return {
    draftQuoteId: DEMO_QUOTE_ID,
    status: "NEEDS_REVIEW",
    resolvedCustomer: { id: "c1", displayName: "Demo Customer", status: "ACTIVE" },
    lines: [
      {
        id: "line-1",
        lineNumber: 1,
        rawSkuOrAlias: "PAD-OE-04465",
        productName: "Brake pads",
        quantity: 2,
        uom: "EA",
        unitPrice: 10,
        marginPercent: 5,
        validationStatus: "VALID"
      }
    ],
    validationIssues: [],
    substituteCandidates: [],
    approvalRequired: false,
    approvalReasons: [],
    approvalRequests: []
  };
}

function sampleApprovalState() {
  return {
    quoteId: DEMO_QUOTE_ID,
    status: "NEEDS_REVIEW",
    approvalRequired: false,
    blockingIssues: [],
    approvalReasons: [],
    approvalRequests: [],
    externalExecutionEnabled: false
  };
}

function sampleApprovalCommand(decision) {
  return {
    quoteId: DEMO_QUOTE_ID,
    previousStatus: "NEEDS_REVIEW",
    newStatus: decision === "CONVERT" ? "CONVERTED_TO_INTERNAL_ORDER" : "APPROVED",
    approvalRequired: false,
    approvalDecision: decision,
    blockingIssues: [],
    approvalReasons: [],
    externalExecutionEnabled: false
  };
}

const server = createServer(async (req, res) => {
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
    return json(res, 200, []);
  }
  if (/^\/api\/v1\/quote-review\/[^/]+\/assemble-draft$/.test(path) && req.method === "POST") {
    return json(res, 200, { ok: true, draftStatus: "DRAFT_ASSEMBLED" });
  }

  if (path === "/api/v1/quotes/from-rfq" && req.method === "POST") {
    try {
      await readJsonBody(req);
    } catch {
      return json(res, 400, { message: "invalid json" });
    }
    return json(res, 200, sampleQuoteTransaction());
  }
  if (path === `/api/v1/quotes/${DEMO_QUOTE_ID}/approval-state` && req.method === "GET") {
    return json(res, 200, sampleApprovalState());
  }
  if (path === `/api/v1/quotes/${DEMO_QUOTE_ID}/approve` && req.method === "POST") {
    return json(res, 200, sampleApprovalCommand("APPROVE"));
  }
  if (path === `/api/v1/quotes/${DEMO_QUOTE_ID}/reject` && req.method === "POST") {
    return json(res, 200, sampleApprovalCommand("REJECT"));
  }
  if (path === `/api/v1/quotes/${DEMO_QUOTE_ID}/request-changes` && req.method === "POST") {
    return json(res, 200, sampleApprovalCommand("REQUEST_CHANGES"));
  }
  if (path === `/api/v1/quotes/${DEMO_QUOTE_ID}/convert-to-internal-order` && req.method === "POST") {
    return json(res, 200, sampleApprovalCommand("CONVERT"));
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
