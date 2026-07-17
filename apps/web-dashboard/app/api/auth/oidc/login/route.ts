import { handleOidcLogin } from "@/lib/bff/bff-auth-handlers";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return handleOidcLogin(request);
}
