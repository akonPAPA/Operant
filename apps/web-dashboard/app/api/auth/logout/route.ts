import { NextResponse } from "next/server";
import { BFF_CSRF_COOKIE, BFF_SESSION_COOKIE } from "@/lib/bff/bff-config";

export async function POST() {
  const response = NextResponse.json({ ok: true });
  for (const name of [BFF_SESSION_COOKIE, BFF_CSRF_COOKIE]) {
    response.cookies.set(name, "", { path: "/", maxAge: 0 });
  }
  return response;
}
