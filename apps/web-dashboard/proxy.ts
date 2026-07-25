import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import {
  MIDDLEWARE_SECURITY_HEADERS,
  decideEdgeMiddleware
} from "./lib/edge-middleware-core.ts";

const BFF_SESSION_COOKIE = "op_session";

function middlewareBffRuntimeEnabled(): boolean {
  if (process.env.NODE_ENV === "production") {
    return process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE !== "true" && process.env.ORDERPILOT_BFF_ENABLED === "true";
  }
  return process.env.ORDERPILOT_BFF_ENABLED === "true";
}

/** Cookie presence only — never authentication. */
function hasSessionCookie(request: NextRequest): boolean {
  const value = request.cookies.get(BFF_SESSION_COOKIE)?.value?.trim();
  return Boolean(value && value.length >= 16);
}

function applySecurityHeaders(response: NextResponse): NextResponse {
  for (const [name, value] of Object.entries(MIDDLEWARE_SECURITY_HEADERS)) {
    response.headers.set(name, value);
  }
  return response;
}

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const decision = decideEdgeMiddleware({
    pathname,
    search,
    enabled: middlewareBffRuntimeEnabled(),
    hasSessionCookie: hasSessionCookie(request)
  });

  if (decision.kind === "json-401") {
    return applySecurityHeaders(
      NextResponse.json({ message: "Authentication is required." }, { status: 401 })
    );
  }
  if (decision.kind === "redirect") {
    return applySecurityHeaders(NextResponse.redirect(new URL(decision.location, request.url)));
  }
  return applySecurityHeaders(NextResponse.next());
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"]
};
