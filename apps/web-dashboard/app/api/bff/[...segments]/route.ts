import { proxyCoreRequest } from "@/lib/bff/bff-proxy";

// Authoritative session validation and proxying require the Node runtime (redis, node:crypto).
export const runtime = "nodejs";

type RouteContext = { params: Promise<{ segments?: string[] }> };

/**
 * F1 security-order: the catch-all handler resolves the path params and delegates directly to
 * proxyCoreRequest. It MUST NOT read, clone, decode, or parse the request body. All authority
 * (mode/config → route → session → permission → same-origin → CSRF) and all body processing
 * (one bounded read → content-type/UTF-8 → duplicate-key → strict allowlist → quote schema →
 * idempotency → signing) are owned by proxyCoreRequest, so no expensive body work — and no quote
 * business-schema evaluation — ever runs before the request is authenticated and authorized.
 */
async function handle(request: Request, context: RouteContext): Promise<Response> {
  const { segments = [] } = await context.params;
  return proxyCoreRequest(request, segments);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
