import { dashboardApiFetch } from "./dashboard-http.ts";

/** Browser/server isomorphic dashboard API fetch (BFF-aware). */
export async function dashboardFetch(input: string, init?: RequestInit): Promise<Response> {
  const path = input.startsWith("/") ? input : `/${input}`;
  return dashboardApiFetch(path, init);
}
