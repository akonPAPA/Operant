import { resolveIdempotencyKey } from "./bff-idempotency-key.ts";

const SAFE_FAILURE = "The request could not be completed.";
const JSON_CONTENT_TYPE = "application/json";
const MAX_BODY_BYTES = 256 * 1024;
const MAX_JSON_DEPTH = 32;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

type QuoteMutationKind =
  | "from-rfq"
  | "approve"
  | "reject"
  | "request-changes"
  | "convert-to-internal-order";

type BodyReadResult =
  | { ok: true; bytes: Uint8Array }
  | { ok: false; status: 400 | 413 };

function safeJson(status: number): Response {
  return new Response(JSON.stringify({ message: SAFE_FAILURE }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

function quoteMutationKind(segments: readonly string[], method: string): QuoteMutationKind | null {
  if (method !== "POST") return null;
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

function declaredContentLength(request: Request): number | null {
  const raw = request.headers.get("content-length");
  if (raw === null) return null;
  if (!/^[0-9]{1,12}$/.test(raw)) return Number.NaN;
  return Number.parseInt(raw, 10);
}

async function readBodyBounded(request: Request): Promise<BodyReadResult> {
  const declared = declaredContentLength(request);
  if (Number.isNaN(declared) || (declared !== null && declared > MAX_BODY_BYTES)) {
    await request.body?.cancel().catch(() => undefined);
    return { ok: false, status: 413 };
  }
  if (!request.body) return { ok: true, bytes: new Uint8Array(0) };

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        await reader.cancel().catch(() => undefined);
        return { ok: false, status: 413 };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, status: 400 };
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { ok: true, bytes };
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

function parseJsonObject(bytes: Uint8Array): Record<string, unknown> | null {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
  if (text.length > 0 && text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  try {
    new JsonDuplicateKeyGuard(text).verify();
    const parsed: unknown = JSON.parse(text);
    return isPlainObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function onlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const allowedSet = new Set(allowed);
  return Object.keys(value).every((key) => allowedSet.has(key));
}

function boundedString(
  value: unknown,
  options: { required?: boolean; min?: number; max: number; nonBlank?: boolean }
): boolean {
  if (value === undefined) return !options.required;
  if (typeof value !== "string" || value.length < (options.min ?? 0) || value.length > options.max) return false;
  if (options.nonBlank && value.trim().length === 0) return false;
  return !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value);
}

function boundedNumber(
  value: unknown,
  options: { required?: boolean; min: number; max: number }
): boolean {
  if (value === undefined) return !options.required;
  return typeof value === "number" && Number.isFinite(value) && value >= options.min && value <= options.max;
}

function validRfqBody(body: Record<string, unknown>): boolean {
  if (!onlyKeys(body, ["customerExternalRef", "requestedLocation", "requestedDiscountPercent", "requestedItems"])) {
    return false;
  }
  if (!boundedString(body.customerExternalRef, { required: true, min: 1, max: 128, nonBlank: true })) return false;
  if (!boundedString(body.requestedLocation, { max: 128, nonBlank: true })) return false;
  if (!boundedNumber(body.requestedDiscountPercent, { min: 0, max: 100 })) return false;
  if (!Array.isArray(body.requestedItems) || body.requestedItems.length < 1 || body.requestedItems.length > 100) {
    return false;
  }
  for (const item of body.requestedItems) {
    if (!isPlainObject(item) || !onlyKeys(item, ["rawSkuOrAlias", "description", "quantity", "uom"])) return false;
    if (!boundedString(item.rawSkuOrAlias, { required: true, min: 1, max: 128, nonBlank: true })) return false;
    if (!boundedString(item.description, { max: 512 })) return false;
    if (!boundedNumber(item.quantity, { required: true, min: Number.MIN_VALUE, max: 1_000_000_000 })) return false;
    if (!boundedString(item.uom, { required: true, min: 1, max: 32, nonBlank: true })) return false;
  }
  return true;
}

function validApprovalBody(kind: QuoteMutationKind, body: Record<string, unknown>): boolean {
  if (!onlyKeys(body, ["approvalRequestId", "reason", "comment"])) return false;
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
 * High-authority quote mutations are stricter than the generic BFF proxy:
 * a canonical opaque Idempotency-Key and a bounded, duplicate-free business-intent JSON body
 * are mandatory. This guard runs before Core forwarding, so every rejection produces zero Core calls.
 */
export async function validateBffQuoteMutationRequest(
  request: Request,
  segments: readonly string[]
): Promise<Response | null> {
  const kind = quoteMutationKind(segments, request.method.toUpperCase());
  if (!kind) return null;

  const idempotency = resolveIdempotencyKey(request.headers.get("idempotency-key"), "required");
  if (!idempotency.ok) return safeJson(400);

  const contentType = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
  if (contentType !== JSON_CONTENT_TYPE) return safeJson(415);

  const bodyResult = await readBodyBounded(request.clone());
  if (!bodyResult.ok) return safeJson(bodyResult.status);
  if (bodyResult.bytes.byteLength === 0) return safeJson(400);

  const body = parseJsonObject(bodyResult.bytes);
  if (!body) return safeJson(400);
  const valid = kind === "from-rfq" ? validRfqBody(body) : validApprovalBody(kind, body);
  return valid ? null : safeJson(400);
}
