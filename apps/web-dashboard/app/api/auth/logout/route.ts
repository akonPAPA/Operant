import { handleLogout } from "@/lib/bff/bff-auth-handlers";

export async function POST(request: Request): Promise<Response> {
  return handleLogout(request);
}
