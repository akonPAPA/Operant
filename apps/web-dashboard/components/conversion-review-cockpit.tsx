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

const demoTenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "11111111-1111-4111-8111-111111111111";

export function ConversionReviewList() {
  const [tenantId, setTenantId] = useState(demoTenantId);
  const [filter, setFilter] = useState<QuoteConversionAttemptReviewFilter>({});
  const [attempts, setAttempts] = useState<QuoteConversionAttemptReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    await loadConversionState(event, () => getQuoteConversionAttempts(tenantId, filter), setAttempts, setError, setLoading, [], "Conversion review failed.");
  }, [filter, tenantId]);

  useEffect(() => {
    return loadInitialConversionState(() => getQuoteConversionAttempts(demoTenantId), setAttempts, setError, setLoading, "Conversion review failed.");
  }, []);

  return (
    <div className="stack">
      <ReadOnlyNotice />
      <section className="panel">
        <h2>Conversion Attempts</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
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
            {loading ? <tr><td colSpan={9}>Loading conversion attempts...</td></tr> : null}
            {!loading && !error && attempts.length === 0 ? <tr><td colSpan={9}>No conversion attempts match the current filters.</td></tr> : null}
            {!loading && attempts.map((attempt) => (
              <tr key={attempt.id}>
                <td><strong>{shortId(attempt.id)}</strong><br /><span className="muted-copy">{attempt.requestMode ?? "review"}</span></td>
                <td>{humanize(attempt.sourceType)}<br /><span className="muted-copy">{attempt.sourceChannel ?? "n/a"} / {shortId(attempt.sourceId)}</span></td>
                <td><StatusPill value={attempt.status} /></td>
                <td>{attempt.reviewRequired ? <span className="status-pill warning">Review Required</span> : <span className="status-pill done">No Review</span>}<br /><span className="muted-copy">{attempt.reasonCode ? humanize(attempt.reasonCode) : "No reason code"}</span></td>
                <td>{attempt.draftQuoteLinked ? <span className="status-pill done">Draft-linked</span> : <span className="status-pill warning">Pre-draft</span>}<br /><span className="muted-copy">{attempt.draftQuoteId ? shortId(attempt.draftQuoteId) : "No draft quote"}</span></td>
                <td>{attempt.issueCount}</td>
                <td>{attempt.lineCount}</td>
                <td>{formatDate(attempt.createdAt)}</td>
                <td><Link className="button secondary-button table-link-button" href={`/conversion-review/${attempt.id}?tenantId=${encodeURIComponent(tenantId)}`}>Open</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

export function ConversionReviewDetail({ attemptId, initialTenantId = demoTenantId }: { attemptId: string; initialTenantId?: string }) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [detail, setDetail] = useState<QuoteConversionAttemptReviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    await loadConversionState(event, () => getQuoteConversionAttemptDetail(tenantId, attemptId), setDetail, setError, setLoading, null, "Conversion attempt detail failed.");
  }, [attemptId, tenantId]);

  useEffect(() => {
    return loadInitialConversionState(() => getQuoteConversionAttemptDetail(initialTenantId, attemptId), setDetail, setError, setLoading, "Conversion attempt detail failed.");
  }, [attemptId, initialTenantId]);

  return (
    <div className="stack">
      <ReadOnlyNotice />
      <section className="panel">
        <h2>Attempt {shortId(attemptId)}</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
          <button className="button" disabled={loading} type="submit">{loading ? "Loading..." : "Reload"}</button>
        </form>
        {error ? <p className="form-message error">{safeError(error)}</p> : null}
      </section>

      {loading ? <section className="panel"><h2>Loading</h2><p>Loading conversion attempt detail...</p></section> : null}
      {!loading && !error && !detail ? <section className="panel"><h2>No attempt</h2><p>No conversion attempt was returned by the backend.</p></section> : null}
      {detail ? (
        <>
          <section className="panel">
            <h2>Review Summary</h2>
            <dl className="detail-list">
              <KeyValue label="Status" value={humanize(detail.status)} />
              <KeyValue label="Review required" value={detail.reviewRequired ? "Yes" : "No"} />
              <KeyValue label="Reason" value={detail.reasonCode ? humanize(detail.reasonCode) : "None"} />
              <KeyValue label="Draft state" value={detail.draftQuoteLinked ? `Draft-linked ${shortId(detail.draftQuoteId)}` : "Pre-draft / no draft quote"} />
              <KeyValue label="Customer resolution" value={detail.customerResolution ?? "n/a"} />
              <KeyValue label="Created" value={formatDate(detail.createdAt)} />
            </dl>
          </section>

          <section className="panel">
            <h2>Source Context</h2>
            <dl className="detail-list">
              <KeyValue label="Source type" value={humanize(detail.sourceType)} />
              <KeyValue label="Source channel" value={detail.sourceChannel ?? "n/a"} />
              <KeyValue label="Source id" value={detail.sourceId} />
              <KeyValue label="Channel message" value={detail.channelMessageId ?? "n/a"} />
              <KeyValue label="Inbound document" value={detail.inboundDocumentId ?? "n/a"} />
              <KeyValue label="Triggered by" value={detail.triggeredBy ? `${shortId(detail.triggeredBy)} / ${detail.triggeredByType ?? "n/a"}` : detail.triggeredByType ?? "n/a"} />
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
                    <td>{issue.lineId ? shortId(issue.lineId) : "Source"}</td>
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

async function loadConversionState<T>(
  event: FormEvent<HTMLFormElement> | undefined,
  loadData: () => Promise<T>,
  setData: (value: T) => void,
  setError: (value: string) => void,
  setLoading: (value: boolean) => void,
  fallback: T,
  errorMessage: string
) {
  event?.preventDefault();
  setLoading(true);
  setError("");
  try {
    setData(await loadData());
  } catch (nextError) {
    setError(nextError instanceof Error ? nextError.message : errorMessage);
    setData(fallback);
  } finally {
    setLoading(false);
  }
}

function loadInitialConversionState<T>(
  loadData: () => Promise<T>,
  setData: (value: T) => void,
  setError: (value: string) => void,
  setLoading: (value: boolean) => void,
  errorMessage: string
) {
  let cancelled = false;
  loadData()
    .then((nextData) => {
      if (!cancelled) setData(nextData);
    })
    .catch((nextError) => {
      if (!cancelled) setError(nextError instanceof Error ? nextError.message : errorMessage);
    })
    .finally(() => {
      if (!cancelled) setLoading(false);
    });
  return () => {
    cancelled = true;
  };
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

function shortId(value?: string | null) {
  if (!value) return "n/a";
  return value.length > 8 ? value.slice(0, 8) : value;
}

function humanize(value?: string | null) {
  if (!value) return "n/a";
  return value.replaceAll("_", " ").replaceAll(".", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function safeError(value: string) {
  return value.length > 180 ? `${value.slice(0, 180)}...` : value;
}
