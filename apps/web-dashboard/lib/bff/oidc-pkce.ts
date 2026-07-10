import { cookies } from "next/headers";
import { NextResponse } from "next/server";

const STATE_COOKIE = "op_oidc_state";
const VERIFIER_COOKIE = "op_oidc_verifier";

export async function readOidcPkceCookies(): Promise<{ state: string; verifier: string } | null> {
  const jar = await cookies();
  const state = jar.get(STATE_COOKIE)?.value;
  const verifier = jar.get(VERIFIER_COOKIE)?.value;
  if (!state || !verifier) {
    return null;
  }
  return { state, verifier };
}

export function setOidcPkceCookies(response: NextResponse, state: string, verifier: string): void {
  const cookieBase = {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
    maxAge: 600
  };
  response.cookies.set(STATE_COOKIE, state, cookieBase);
  response.cookies.set(VERIFIER_COOKIE, verifier, cookieBase);
}

export function clearOidcPkceCookies(response: NextResponse): void {
  for (const name of [STATE_COOKIE, VERIFIER_COOKIE]) {
    response.cookies.set(name, "", { path: "/", maxAge: 0 });
  }
}
