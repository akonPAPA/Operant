import { readBrowserCsrfTokenFromDocument } from "../browser-csrf-cookie.ts";

/** Client-side CSRF header for BFF mutations (cookie is readable; session cookie is httpOnly). */
export function readBffCsrfTokenFromDocument(): string {
  return readBrowserCsrfTokenFromDocument() ?? "";
}

export function bffMutationHeaders(): Record<string, string> {
  const csrf = readBffCsrfTokenFromDocument();
  return csrf ? { "X-OP-CSRF-Token": csrf } : {};
}
