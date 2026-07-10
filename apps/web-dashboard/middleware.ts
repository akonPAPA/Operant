import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { BFF_SESSION_COOKIE, bffRuntimeMode, bffSessionSecret } from "@/lib/bff/bff-config";
import { parseSessionToken } from "@/lib/bff/bff-session";
import { validateBffProductionConfig } from "@/lib/bff/bff-proxy";

const PUBLIC_PATH_PREFIXES = ["/login", "/api/auth", "/public", "/api/bff/health"];
const STATIC_PREFIXES = ["/_next", "/favicon.ico"];

function isPublicPath(pathname: string): boolean {
  if (STATIC_PREFIXES.some((p) => pathname.startsWith(p))) {
    return true;
  }
  return PUBLIC_PATH_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

export function middleware(request: NextRequest) {
  const configError = validateBffProductionConfig();
  if (configError && bffRuntimeMode() === "bff-production") {
    return new NextResponse("BFF configuration invalid", { status: 503 });
  }

  const response = NextResponse.next();
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("X-Frame-Options", "DENY");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("Permissions-Policy", "geolocation=()");
  response.headers.set("Cache-Control", "no-store");

  if (bffRuntimeMode() !== "bff-production") {
    return response;
  }

  const { pathname } = request.nextUrl;
  if (isPublicPath(pathname)) {
    return response;
  }

  const session = parseSessionToken(
    request.cookies.get(BFF_SESSION_COOKIE)?.value,
    bffSessionSecret()
  );
  if (!session) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname);
    return NextResponse.redirect(login);
  }

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"]
};
