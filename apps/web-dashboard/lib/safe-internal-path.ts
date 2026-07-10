/**
 * Login redirect validation: accept only a same-origin absolute path with exactly one
 * leading "/" — no "//", no scheme, no backslash, no control characters, and no
 * percent-encoded (including multiply-encoded) variant that decodes into an external
 * redirect. Anything else resolves to "/".
 */
const MAX_DECODE_DEPTH = 5;

export function safeInternalPath(raw: string | null | undefined): string {
  if (!raw) {
    return "/";
  }
  if (!raw.startsWith("/") || raw.startsWith("//")) {
    return "/";
  }
  const forbidden = /[\\\u0000-\u001f\u007f]/;
  let layer = raw;
  for (let depth = 0; depth <= MAX_DECODE_DEPTH; depth += 1) {
    if (forbidden.test(layer)) {
      return "/";
    }
    if (layer.includes("//")) {
      return "/";
    }
    if (layer.includes("://") || /^\/*[a-zA-Z][a-zA-Z0-9+.-]*:/.test(layer)) {
      return "/";
    }
    let decoded: string;
    try {
      decoded = decodeURIComponent(layer);
    } catch {
      return "/";
    }
    if (decoded === layer) {
      return raw;
    }
    layer = decoded;
  }
  // still decoding after MAX_DECODE_DEPTH layers — refuse
  return "/";
}
