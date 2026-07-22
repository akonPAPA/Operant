/**
 * High-authority quote mutation contract (route-specific business payload schema).
 *
 * This module answers exactly ONE question the generic proxy cannot: for a tenant quote-authority
 * mutation (from-rfq / approve / reject / request-changes / convert-to-internal-order), does the
 * already-authenticated, already-read request body decode to a bounded, duplicate-key-free
 * business-intent JSON object whose VALUES satisfy the route schema?
 *
 * F1 security-order: this module does NOT read/clone the request body, does NOT authenticate, and
 * does NOT check permission / same-origin / CSRF / idempotency. Those are owned by the proxy and
 * run BEFORE any of the work here. It also does NOT own the top-level key allowlist — that is the
 * route registry `bodyPolicy`, applied by the proxy via bodyMatchesStrictPolicy. The proxy composes
 * the single body-processing path:
 *
 *   bounded body read (once)
 *     -> parseStrictQuoteJsonBody  (fatal UTF-8 + duplicate-key rejection + plain-object)
 *     -> bodyMatchesStrictPolicy   (strict key allowlist — the ONLY allowlist)
 *     -> validateQuoteMutationBody (value / required-field schema)
 *
 * Every rejection returns before signing, so a rejected body produces zero Core calls. Duplicate
 * keys are rejected from the RAW bytes (parseStrictQuoteJsonBody) — never JSON.parse-first, which
 * would silently collapse duplicates and destroy the evidence.
 */

const MAX_JSON_DEPTH = 32;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
// Reject C0/C1 control characters (allowing \t \n \r) and DEL — business intent text is single-line.
const CONTROL_CHAR = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

export type QuoteMutationKind =
  | "from-rfq"
  | "approve"
  | "reject"
  | "request-changes"
  | "convert-to-internal-order";

export type StrictQuoteJsonBody =
  | { ok: true; object: Record<string, unknown> }
  | { ok: false };

/**
 * Classify a registered path as a tenant quote-authority mutation, or null for anything else.
 * Structural route existence/method/permission remain the route registry's authority; this only
 * selects the additional business schema the proxy must apply after the generic allowlist.
 */
export function quoteMutationKind(
  segments: readonly string[],
  method: string
): QuoteMutationKind | null {
  if (method.toUpperCase() !== "POST") return null;
  if (
    segments.length === 4 &&
    segments[0] === "api" &&
    segments[1] === "v1" &&
    segments[2] === "quotes" &&
    segments[3] === "from-rfq"
  ) {
    return "from-rfq";
  }
  if (
    segments.length !== 5 ||
    segments[0] !== "api" ||
    segments[1] !== "v1" ||
    segments[2] !== "quotes" ||
    !UUID_VALUE.test(segments[3])
  ) {
    return null;
  }
  const action = segments[4];
  return action === "approve" ||
    action === "reject" ||
    action === "request-changes" ||
    action === "convert-to-internal-order"
    ? action
    : null;
}

class JsonDuplicateKeyGuard {
  private index = 0;
  private readonly text: string;

  constructor(text: string) {
    this.text = text;
  }

  verify(): void {
    this.skipWhitespace();
    this.parseValue(0);
    this.skipWhitespace();
    if (this.index !== this.text.length) throw new Error("trailing JSON data");
  }

  private parseValue(depth: number): void {
    if (depth > MAX_JSON_DEPTH) throw new Error("JSON nesting limit exceeded");
    this.skipWhitespace();
    const value = this.text[this.index];
    if (value === "{") return this.parseObject(depth + 1);
    if (value === "[") return this.parseArray(depth + 1);
    if (value === '"') {
      this.parseString();
      return;
    }
    if (value === "t") return this.consumeLiteral("true");
    if (value === "f") return this.consumeLiteral("false");
    if (value === "n") return this.consumeLiteral("null");
    this.parseNumber();
  }

  private parseObject(depth: number): void {
    this.expect("{");
    this.skipWhitespace();
    if (this.peek("}")) {
      this.index += 1;
      return;
    }
    const keys = new Set<string>();
    while (true) {
      this.skipWhitespace();
      const key = this.parseString();
      if (keys.has(key)) throw new Error("duplicate JSON key");
      keys.add(key);
      this.skipWhitespace();
      this.expect(":");
      this.parseValue(depth);
      this.skipWhitespace();
      if (this.peek("}")) {
        this.index += 1;
        return;
      }
      this.expect(",");
    }
  }

  private parseArray(depth: number): void {
    this.expect("[");
    this.skipWhitespace();
    if (this.peek("]")) {
      this.index += 1;
      return;
    }
    while (true) {
      this.parseValue(depth);
      this.skipWhitespace();
      if (this.peek("]")) {
        this.index += 1;
        return;
      }
      this.expect(",");
    }
  }

  private parseString(): string {
    const start = this.index;
    this.expect('"');
    while (this.index < this.text.length) {
      const char = this.text[this.index++];
      if (char === '"') {
        return JSON.parse(this.text.slice(start, this.index)) as string;
      }
      if (char === "\\") {
        const escaped = this.text[this.index++];
        if (escaped === "u") {
          const hex = this.text.slice(this.index, this.index + 4);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) throw new Error("invalid unicode escape");
          this.index += 4;
        } else if (!'"\\/bfnrt'.includes(escaped ?? "")) {
          throw new Error("invalid JSON escape");
        }
      } else if (char.charCodeAt(0) <= 0x1f) {
        throw new Error("control character in JSON string");
      }
    }
    throw new Error("unterminated JSON string");
  }

  private parseNumber(): void {
    const match = this.text.slice(this.index).match(/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/);
    if (!match) throw new Error("invalid JSON value");
    this.index += match[0].length;
  }

  private consumeLiteral(literal: string): void {
    if (!this.text.startsWith(literal, this.index)) throw new Error("invalid JSON literal");
    this.index += literal.length;
  }

  private skipWhitespace(): void {
    while (/[\t\n\r ]/.test(this.text[this.index] ?? "")) this.index += 1;
  }

  private expect(value: string): void {
    if (!this.peek(value)) throw new Error(`expected ${value}`);
    this.index += value.length;
  }

  private peek(value: string): boolean {
    return this.text.startsWith(value, this.index);
  }
}

/**
 * Decode already-read request bytes into a plain JSON object, rejecting (ok:false) on invalid
 * UTF-8, malformed JSON, a duplicate object key (scanned on the raw text before JSON.parse can
 * collapse it), or a non-object top-level value. A leading UTF-8 BOM is stripped once.
 */
export function parseStrictQuoteJsonBody(bytes: Uint8Array): StrictQuoteJsonBody {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return { ok: false };
  }
  if (text.length > 0 && text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  try {
    new JsonDuplicateKeyGuard(text).verify();
    const parsed: unknown = JSON.parse(text);
    return isPlainObject(parsed) ? { ok: true, object: parsed } : { ok: false };
  } catch {
    return { ok: false };
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function boundedString(
  value: unknown,
  options: { required?: boolean; min?: number; max: number; nonBlank?: boolean }
): boolean {
  if (value === undefined) return !options.required;
  if (typeof value !== "string" || value.length < (options.min ?? 0) || value.length > options.max) return false;
  if (options.nonBlank && value.trim().length === 0) return false;
  return !CONTROL_CHAR.test(value);
}

function boundedNumber(
  value: unknown,
  options: { required?: boolean; min: number; max: number }
): boolean {
  if (value === undefined) return !options.required;
  return typeof value === "number" && Number.isFinite(value) && value >= options.min && value <= options.max;
}

function validRfqBody(body: Record<string, unknown>): boolean {
  if (!boundedString(body.customerExternalRef, { required: true, min: 1, max: 128, nonBlank: true })) return false;
  if (!boundedString(body.requestedLocation, { max: 128, nonBlank: true })) return false;
  if (!boundedNumber(body.requestedDiscountPercent, { min: 0, max: 100 })) return false;
  if (!Array.isArray(body.requestedItems) || body.requestedItems.length < 1 || body.requestedItems.length > 100) {
    return false;
  }
  for (const item of body.requestedItems) {
    if (!isPlainObject(item)) return false;
    if (!boundedString(item.rawSkuOrAlias, { required: true, min: 1, max: 128, nonBlank: true })) return false;
    if (!boundedString(item.description, { max: 512 })) return false;
    if (!boundedNumber(item.quantity, { required: true, min: Number.MIN_VALUE, max: 1_000_000_000 })) return false;
    if (!boundedString(item.uom, { required: true, min: 1, max: 32, nonBlank: true })) return false;
  }
  return true;
}

function validApprovalBody(kind: QuoteMutationKind, body: Record<string, unknown>): boolean {
  if (
    body.approvalRequestId !== undefined &&
    (typeof body.approvalRequestId !== "string" || !UUID_VALUE.test(body.approvalRequestId))
  ) {
    return false;
  }
  if (!boundedString(body.reason, { max: 2000 })) return false;
  if (!boundedString(body.comment, { max: 2000 })) return false;
  if (kind === "reject" || kind === "request-changes") {
    const reason = typeof body.reason === "string" ? body.reason.trim() : "";
    const comment = typeof body.comment === "string" ? body.comment.trim() : "";
    if (!reason && !comment) return false;
  }
  return true;
}

/**
 * Validate the VALUE/required-field schema for a quote-authority mutation. The top-level key
 * allowlist is NOT re-checked here — the proxy applies the route registry `bodyPolicy` (the single
 * allowlist) immediately before calling this, so unknown / authority / server-state /
 * prototype-pollution keys have already failed closed.
 */
export function validateQuoteMutationBody(
  kind: QuoteMutationKind,
  body: Record<string, unknown>
): boolean {
  return kind === "from-rfq" ? validRfqBody(body) : validApprovalBody(kind, body);
}
