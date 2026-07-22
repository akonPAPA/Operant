import { handleSessionPermissionsPatch } from "@/lib/bff/bff-local-test-session-handlers";

export async function PATCH(request: Request) {
  return handleSessionPermissionsPatch(request);
}
