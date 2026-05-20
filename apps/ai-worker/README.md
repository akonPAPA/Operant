# AI Worker

Python 3.12+ skeleton for OCR, text extraction, and LLM-assisted advisory extraction.

The worker must not connect directly to trusted business tables and must not create orders, quotes, inventory changes, price changes, customer changes, product changes, or ERP writes.

Stage 3 queues `DOCUMENT_PROCESSING`, `MESSAGE_PROCESSING`, and `ATTACHMENT_PROCESSING` jobs. Stage 4 adds advisory extraction schemas and mock providers.

Stage 5 deterministic validation is owned by `apps/core-api`, not this worker. AI extraction output is only input to backend validation; the worker must not run business validation, approve substitutions/discounts/margins, write product/customer/price/inventory/order/quote data, or call ERP/1C/accounting/warehouse systems.

Stage 6 quote/order workspace and exception cockpit are also owned by `apps/core-api` and the operator dashboard. The AI worker must not create quotes or orders, approve issues, select substitutes as final decisions, write workflow/master data, send customer replies, or call ERP/1C/accounting/warehouse systems.

## Run

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
python -m orderpilot_ai_worker.main
```

## Test

```powershell
pytest
```
