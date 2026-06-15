"use client";

import { useState } from "react";

import { coreApiBaseUrl, coreApiStatusMessage, demoScopeHeaders, hasDemoScope, missingDemoScopeMessage } from "@/lib/core-api-client";

const ACCEPTED_TYPES = ".pdf,.csv,.xlsx,.xls,.txt,.png,.jpg,.jpeg";

type UploadState =
  | { status: "idle"; message: string }
  | { status: "working"; message: string }
  | { status: "done"; message: string }
  | { status: "error"; message: string };

export function IntakeUploadForm() {
  const [state, setState] = useState<UploadState>({ status: "idle", message: "No upload submitted." });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const formData = new FormData(form);
    const file = formData.get("file");
    if (!(file instanceof File) || file.size === 0) {
      setState({ status: "error", message: "Choose a supported file before uploading." });
      return;
    }
    if (!hasDemoScope()) {
      setState({ status: "error", message: missingDemoScopeMessage("tenant-scoped uploads") });
      return;
    }

    setState({ status: "working", message: "Uploading through core-api..." });
    try {
      const response = await fetch(`${coreApiBaseUrl()}/api/v1/intake/documents/upload`, {
        method: "POST",
        headers: demoScopeHeaders(),
        body: formData
      });
      if (!response.ok) {
        // Map to operator-safe messages; never surface the raw backend body.
        setState({ status: "error", message: coreApiStatusMessage(response.status) });
        return;
      }
      const text = await response.text();
      const result = JSON.parse(text) as { status?: string; originalFilename?: string };
      setState({ status: "done", message: `${result.originalFilename ?? "Document"} accepted with status ${result.status ?? "RECEIVED"}.` });
      form.reset();
    } catch (error) {
      setState({ status: "error", message: error instanceof Error ? error.message : "Upload failed." });
    }
  }

  return (
    <form className="panel upload-form" onSubmit={submit}>
      <label>
        <span>Document</span>
        <input name="file" type="file" accept={ACCEPTED_TYPES} />
      </label>
      <label>
        <span>Source channel</span>
        <select name="sourceChannel" defaultValue="FILE_UPLOAD">
          <option value="FILE_UPLOAD">FILE_UPLOAD</option>
          <option value="API_UPLOAD">API_UPLOAD</option>
          <option value="EMAIL">EMAIL</option>
          <option value="TELEGRAM">TELEGRAM</option>
          <option value="WHATSAPP">WHATSAPP</option>
        </select>
      </label>
      <label>
        <span>Document type</span>
        <input name="documentType" defaultValue="CUSTOMER_RFQ" />
      </label>
      <label>
        <span>Received from</span>
        <input name="receivedFrom" placeholder="buyer@example.com" />
      </label>
      <label>
        <span>Subject</span>
        <input name="subject" placeholder="RFQ, stock request, price request" />
      </label>
      <button className="button" disabled={state.status === "working"} type="submit">
        Upload
      </button>
      <p className={`form-message ${state.status}`}>{state.message}</p>
    </form>
  );
}
