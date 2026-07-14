import { proxyCoreRequest } from "@/lib/bff/bff-proxy";

// Authoritative session validation and proxying require the Node runtime (redis, node:crypto).
export const runtime = "nodejs";

type RouteContext = { params: Promise<{ segments?: string[] }> };

async function handle(request: Request, context: RouteContext): Promise<Response> {
  const { segments = [] } = await context.params;
  return proxyCoreRequest(request, segments);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
