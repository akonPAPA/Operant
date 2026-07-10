import { NextResponse } from "next/server";
import { proxyCoreRequest } from "@/lib/bff/bff-proxy";

type RouteContext = { params: Promise<{ segments?: string[] }> };

async function handle(request: Request, context: RouteContext) {
  const { segments = [] } = await context.params;
  return proxyCoreRequest(request, segments);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
