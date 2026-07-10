import { NextResponse } from "next/server";
import { BFF_CSRF_COOKIE, BFF_SESSION_COOKIE, bffSessionSecret } from "@/lib/bff/bff-config";
import { parseSessionToken } from "@/lib/bff/bff-session";
import { isSessionRevoked, revokeSession } from "@/lib/bff/bff-session-revocation";

export async function POST() {
  const secret = bffSessionSecret();
  const cookieHeader = (await import("next/headers")).cookies;
  const jar = await cookieHeader();
  const raw = jar.get(BFF_SESSION_COOKIE)?.value;
  const session = parseSessionToken(raw, secret);
  if (session?.sessionId) {
    revokeSession(session.sessionId);
  }
  const response = NextResponse.json({ ok: true });
  for (const name of [BFF_SESSION_COOKIE, BFF_CSRF_COOKIE]) {
    response.cookies.set(name, "", { path: "/", maxAge: 0 });
  }
  return response;
}
