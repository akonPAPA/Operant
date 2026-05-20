# Extraction Data Model

## Tables

- `extraction_run`: one extraction attempt for an intake source.
- `extracted_document_text`: text extracted before semantic analysis.
- `extraction_result`: advisory structured result.
- `extracted_field`: field-level extraction with confidence.
- `extracted_line_item`: line-level extraction with confidence.
- `source_evidence`: source text/page/message evidence.
- `ai_suggestion`: advisory warnings, summaries, and candidates.
- `prompt_template_version`: prompt/schema metadata without secrets.

All tenant-owned extraction tables include `tenant_id`.

Extraction tables are not business source-of-truth. They prepare a handoff to Stage 5 deterministic validation.