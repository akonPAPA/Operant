import { BFF_CSRF_COOKIE } from "./bff/bff-config.ts";

export { BFF_CSRF_HEADER } from "./bff/bff-config.ts";

export function readCsrfTokenFromDocumentCookie(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const parts = document.cookie.split(";").map((p) => p.trim());
  for (const part of parts) {
    if (part.startsWith(`${BFF_CSRF_COOKIE}=`)) {
      return decodeURIComponent(part.slice(BFF_CSRF_COOKIE.length + 1));
    }
  }
  return null;
}
