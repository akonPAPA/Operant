/** Minimal cookie-header parsing (Edge-safe, dependency-free). First match wins (non-security cookies). */
export function readCookie(cookieHeader: string | null | undefined, name: string): string | undefined {
  if (!cookieHeader) {
    return undefined;
  }
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (trimmed.startsWith(`${name}=`)) {
      const value = trimmed.slice(name.length + 1);
      try {
        return decodeURIComponent(value);
      } catch {
        return undefined;
      }
    }
  }
  return undefined;
}

/**
 * Security cookies (op_session, op_csrf): fail-closed on duplicate name, empty value, or malformed encoding.
 */
export function readSecurityCookieHeader(
  cookieHeader: string | null | undefined,
  name: string
): string | undefined {
  if (!cookieHeader) {
    return undefined;
  }
  const decodedValues: string[] = [];
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (!trimmed.startsWith(`${name}=`)) {
      continue;
    }
    const raw = trimmed.slice(name.length + 1);
    if (raw.length === 0) {
      return undefined;
    }
    try {
      decodedValues.push(decodeURIComponent(raw));
    } catch {
      return undefined;
    }
  }
  if (decodedValues.length === 0) {
    return undefined;
  }
  if (decodedValues.length > 1) {
    return undefined;
  }
  return decodedValues[0];
}
