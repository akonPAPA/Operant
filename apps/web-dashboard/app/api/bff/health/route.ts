import { NextResponse } from "next/server";
import { validateOidcProductionReadiness } from "@/lib/bff/bff-oidc-readiness";

export const runtime = "nodejs";

export async function GET() {
  const readiness = await validateOidcProductionReadiness();
  if (!readiness.ok) {
    return NextResponse.json({ status: "not_ready", code: readiness.code }, { status: 503, headers: { "Cache-Control": "no-store" } });
  }
  return NextResponse.json({ status: "ok" }, { headers: { "Cache-Control": "no-store" } });
}
