# Exception Cockpit

The exception cockpit groups validation problems into operator cases.

```text
ValidationRun
  -> open ValidationIssue records
  -> ExceptionCase
  -> ExceptionCaseIssue
  -> SuggestedFix
  -> ApprovalDecision / WorkspaceNote / OperatorAction
```

Priority is derived from issue severity:

- `CRITICAL` -> `URGENT`
- `ERROR` -> `HIGH`
- `WARNING` -> `NORMAL`
- only `INFO` -> `LOW`

Cases can be assigned, reviewed, resolved, rejected, or cancelled. These are workflow state changes only.
