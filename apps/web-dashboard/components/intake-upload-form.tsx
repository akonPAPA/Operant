"use client";

import { useState } from "react";

const DEFAULT_BASE_URL = "http://localhost:8080";
const ACCEPTED_TYPES = ".pdf,.csv,.xlsx,.xls,.txt,.png,.jpg,.jpeg";

type UploadState =
  | { status: "idle"; message: string }
  | { status: "working"; message: string }
  | { status: "done"; message: string }
  | { status: "error"; message: string };

export function IntakeUploadForm() {
  const [tenantId, setTenantId] = useState(process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "");
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
    if (!tenantId) {
      setState({ status: "error", message: "Tenant ID is required for a tenant-scoped upload." });
      return;
    }

    setState({ status: "working", message: "Uploading through core-api..." });
    try {
      const baseUrl = process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL;
      const response = await fetch(`${baseUrl}/api/v1/intake/documents/upload`, {
        method: "POST",
        headers: { "X-Tenant-Id": tenantId },
        body: formData
      });
      const text = await response.text();
      if (!response.ok) {
        setState({ status: "error", message: text || `Upload rejected with HTTP ${response.status}.` });
        return;
      }
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
        <span>Tenant ID</span>
        <input name="tenantId" value={tenantId} onChange={(event) => setTenantId(event.target.value)} placeholder="UUID" />
      </label>
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
