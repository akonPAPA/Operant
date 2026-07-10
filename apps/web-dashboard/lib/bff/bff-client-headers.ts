/** Client-side CSRF header for BFF mutations (cookie is readable; session cookie is httpOnly). */
export function readBffCsrfTokenFromDocument(): string {
  if (typeof document === "undefined") {
    return "";
  }
  const match = document.cookie.match(/(?:^|;\s*)op_csrf=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

export function bffMutationHeaders(): Record<string, string> {
  const csrf = readBffCsrfTokenFromDocument();
  return csrf ? { "X-OP-CSRF-Token": csrf } : {};
}
