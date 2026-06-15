"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";

import {
  getQuoteConversionAttemptDetail,
  getQuoteConversionAttempts,
  QuoteConversionAttemptReviewDetail,
  QuoteConversionAttemptReviewFilter,
  QuoteConversionAttemptReviewItem
} from "@/lib/quote-review-api";

type LoadKind = "loading" | "ready" | "forbidden" | "not_found" | "error";

export function ConversionReviewList() {
  const [filter, setFilter] = useState<QuoteConversionAttemptReviewFilter>({});
  const [attempts, setAttempts] = useState<QuoteConversionAttemptReviewItem[]>([]);
  const [loadState, setLoadState] = useState<LoadKind>("loading");
  const [error, setError] = useState("");
  const loading = loadState === "loading";

  const apply = useCallback((result: Awaited<ReturnType<typeof getQuoteConversionAttempts>>) => {
    if (result.ok) {
      setAttempts(result.data ?? []);
      setLoadState("ready");
    } else {
      setAttempts([]);
      setLoadState(result.kind === "forbidden" || result.kind === "not_found" ? result.kind : "error");
      setError(result.message);
    }
  }, []);

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setLoadState("loading");
    setError("");
    apply(await getQuoteConversionAttempts(filter));
  }, [filter, apply]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await getQuoteConversionAttempts();
      if (!cancelled) apply(result);
    })();
    return () => {
      cancelled = true;
    };
  }, [apply]);

  return (
    <div className="stack">
      <ReadOnlyNotice />
      <section className="panel">
        <h2>Conversion Attempts</h2>
        <form className="upload-form" onSubmit={load}>
          <div className="control-grid">
            <label><span>Status</span><input value={filter.status ?? ""} onChange={(event) => setFilter({ ...filter, status: event.target.value })} placeholder="NEEDS_REVIEW" /></label>
            <label><span>Reason code</span><input value={filter.reasonCode ?? ""} onChange={(event) => setFilter({ ...filter, reasonCode: event.target.value })} placeholder="CUSTOMER_UNRESOLVED" /></label>
            <label><span>Source channel</span><input value={filter.sourceChannel ?? ""} onChange={(event) => setFilter({ ...filter, sourceChannel: event.target.value })} placeholder="TELEGRAM" /></label>
            <label><span>Review required</span><select value={filter.reviewRequired === undefined ? "" : String(filter.reviewRequired)} onChange={(event) => setFilter({ ...filter, reviewRequired: parseOptionalBoolean(event.target.value) })}><option value="">Any</option><option value="true">Yes</option><option value="false">No</option></select></label>
            <label><span>Draft linked</span><select value={filter.draftQuoteLinked === undefined ? "" : String(filter.draftQuoteLinked)} onChange={(event) => setFilter({ ...filter, draftQuoteLinked: parseOptionalBoolean(event.target.value) })}><option value="">Any</option><option value="false">Pre-draft</option><option value="true">Draft-linked</option></select></label>
          </div>
          <button className="button" disabled={loading} type="submit">{loading ? "Loading..." : "Refresh"}</button>
        </form>
        {error ? <p className="form-message error">{safeError(error)}</p> : null}
      </section>

      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Attempt</th><th>Source</th><th>Status</th><th>Review</th><th>Draft</th><th>Issues</th><th>Lines</th><th>Created</th><th>Open</th></tr></thead>
          <tbody>
            {loadState === "loading" ? <tr><td colSpan={9}>Loading conversion attempts...</td></tr> : null}
            {loadState === "forbidden" || loadState === "not_found" || loadState === "error"
              ? <tr><td colSpan={9}>{safeError(error)}</td></tr>
              : null}
            {loadState === "ready" && attempts.length === 0 ? <tr><td colSpan={9}>No conversion reviews found.</td></tr> : null}
            {loadState === "ready" && attempts.map((attempt) => (
              <tr key={attempt.id}>
                <td><strong>{attempt.requestMode ? humanize(attempt.requestMode) : "Review"}</strong><br /><span className="muted-copy">{formatSourceContext(attempt)}</span></td>
                <td>{humanize(attempt.sourceType)}<br /><span className="muted-copy">{attempt.sourceChannel ?? "Linked intake source"}</span></td>
                <td><StatusPill value={attempt.status} /></td>
                <td>{attempt.reviewRequired ? <span className="status-pill warning">Review Required</span> : <span className="status-pill done">No Review</span>}<br /><span className="muted-copy">{attempt.reasonCode ? humanize(attempt.reasonCode) : "No reason code"}</span></td>
                <td>{attempt.draftQuoteLinked ? <span className="status-pill done">Draft-linked</span> : <span className="status-pill warning">Pre-draft</span>}<br /><span className="muted-copy">{attempt.draftQuoteLinked ? "Internal draft available" : "No draft quote"}</span></td>
                <td>{attempt.issueCount}</td>
                <td>{attempt.lineCount}</td>
                <td>{formatDate(attempt.createdAt)}</td>
                <td><Link className="button secondary-button table-link-button" href={`/conversion-review/${attempt.id}`}>Open</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

export function ConversionReviewDetail({ attemptId }: { attemptId: string }) {
  const [detail, setDetail] = useState<QuoteConversionAttemptReviewDetail | null>(null);
  const [loadState, setLoadState] = useState<LoadKind>("loading");
  const [error, setError] = useState("");
  const loading = loadState === "loading";

  const apply = useCallback((result: Awaited<ReturnType<typeof getQuoteConversionAttemptDetail>>) => {
    if (result.ok) {
      setDetail(result.data ?? null);
      setLoadState(result.data ? "ready" : "not_found");
    } else {
      setDetail(null);
      setLoadState(result.kind === "forbidden" || result.kind === "not_found" ? result.kind : "error");
      setError(result.message);
    }
  }, []);

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setLoadState("loading");
    setError("");
    apply(await getQuoteConversionAttemptDetail(attemptId));
  }, [attemptId, apply]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await getQuoteConversionAttemptDetail(attemptId);
      if (!cancelled) apply(result);
    })();
    return () => {
      cancelled = true;
    };
  }, [attemptId, apply]);

  return (
    <div className="stack">
      <ReadOnlyNotice />
      <section className="panel">
        <h2>Conversion Review Item</h2>
        <form className="upload-form" onSubmit={load}>
          <button className="button" disabled={loading} type="submit">{loading ? "Loading..." : "Reload"}</button>
        </form>
        {error ? <p className="form-message error">{safeError(error)}</p> : null}
      </section>

      {loading ? <section className="panel"><h2>Loading</h2><p>Loading conversion attempt detail...</p></section> : null}
      {loadState === "forbidden" || loadState === "not_found" || loadState === "error"
        ? <section className="panel"><h2>Review unavailable</h2><p className="form-message error">{safeError(error)}</p></section>
        : null}
      {loadState === "ready" && detail ? (
        <>
          <section className="panel">
            <h2>Review Summary</h2>
            <dl className="detail-list">
              <KeyValue label="Status" value={humanize(detail.status)} />
              <KeyValue label="Review required" value={detail.reviewRequired ? "Yes" : "No"} />
              <KeyValue label="Reason" value={detail.reasonCode ? humanize(detail.reasonCode) : "None"} />
              <KeyValue label="Draft state" value={detail.draftQuoteLinked ? "Draft-linked" : "Pre-draft / no draft quote"} />
              <KeyValue label="Customer resolution" value={detail.customerResolution ?? "n/a"} />
              <KeyValue label="Created" value={formatDate(detail.createdAt)} />
            </dl>
          </section>

          <section className="panel">
            <h2>Source Context</h2>
            <dl className="detail-list">
              <KeyValue label="Source type" value={humanize(detail.sourceType)} />
              <KeyValue label="Source channel" value={detail.sourceChannel ?? "n/a"} />
              <KeyValue label="Source reference" value={formatSourceContext(detail)} />
              <KeyValue label="Triggered by" value={detail.triggeredByType ?? "n/a"} />
            </dl>
          </section>

          <section className="panel table-panel">
            <h2>Validation Issues</h2>
            <table className="data-table">
              <thead><tr><th>Code</th><th>Severity</th><th>Blocking</th><th>Message</th><th>Line</th></tr></thead>
              <tbody>
                {detail.validationIssues.length ? detail.validationIssues.map((issue) => (
                  <tr key={`${issue.code}-${issue.lineId ?? "source"}`}>
                    <td>{humanize(issue.code)}</td>
                    <td>{issue.severity}</td>
                    <td>{issue.blocking ? "Yes" : "No"}</td>
                    <td>{issue.message}</td>
                    <td>{issue.lineId ? "Line item" : "Source"}</td>
                  </tr>
                )) : <tr><td colSpan={5}>No validation issues returned for this attempt.</td></tr>}
              </tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Safe Metadata</h2>
            {Object.keys(detail.safeMetadata ?? {}).length ? (
              <dl className="detail-list">
                {Object.entries(detail.safeMetadata).map(([key, value]) => <KeyValue key={key} label={humanize(key)} value={formatValue(value)} />)}
              </dl>
            ) : <p>No safe metadata returned.</p>}
            <p className="risk-note">Raw payloads, raw message text, document text, webhook tokens, connector credentials, secrets, and raw AI output are not displayed by this surface.</p>
          </section>
        </>
      ) : null}
    </div>
  );
}

function ReadOnlyNotice() {
  return (
    <section className="panel">
      <h2>Read-only conversion review</h2>
      <p>This surface displays tenant-scoped backend review data only. It does not approve, reject, retry, correct, create quotes, execute connectors, or write to ERP/1C.</p>
    </section>
  );
}

function StatusPill({ value }: { value: string }) {
  const warning = value.includes("REVIEW") || value.includes("REJECTED") || value.includes("FAILED");
  return <span className={`status-pill ${warning ? "warning" : "done"}`}>{humanize(value)}</span>;
}

function KeyValue({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}

function parseOptionalBoolean(value: string) {
  return value === "" ? undefined : value === "true";
}

function formatDate(value?: string | null) {
  if (!value) return "n/a";
  return new Date(value).toLocaleString();
}

function formatValue(value: string | number | boolean | null) {
  if (value === null || value === undefined) return "n/a";
  return String(value);
}

function formatSourceContext(attempt: Pick<QuoteConversionAttemptReviewItem, "sourceType" | "sourceChannel" | "createdAt">) {
  const channel = attempt.sourceChannel ? humanize(attempt.sourceChannel) : "linked intake source";
  return `${humanize(attempt.sourceType)} via ${channel} (${formatDate(attempt.createdAt)})`;
}

function humanize(value?: string | null) {
  if (!value) return "n/a";
  return value.replaceAll("_", " ").replaceAll(".", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function safeError(value: string) {
  return value.length > 180 ? `${value.slice(0, 180)}...` : value;
}
