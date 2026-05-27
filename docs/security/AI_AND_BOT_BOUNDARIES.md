# AI And Bot Boundaries

## Allowed AI Behavior

AI may:

- extract fields from documents and messages;
- classify intent or risk signals;
- suggest corrections or next actions;
- summarize operator evidence;
- rank candidates for human review.

## Prohibited AI Behavior

AI must not:

- approve quotes or orders;
- approve discounts, substitutes, or exceptions;
- update inventory;
- update prices, margin rules, or customer master data;
- mutate ERP, 1C, accounting, or warehouse systems;
- bypass deterministic validation, review, approval, policy, audit, or ChangeRequest gates.

## Allowed Bot Behavior

The bot may:

- capture inbound messages;
- classify intent through deterministic policy;
- create reviewable handoffs;
- prepare safe operator-visible response drafts or local stub-send state where already implemented.

## Prohibited Bot Behavior

The bot must not:

- trigger connector execution;
- create `ConnectorCommand` records;
- approve external writes;
- approve quotes, orders, discounts, substitutes, or inventory actions;
- search unrestricted tenant data;
- trust customer identity from message text;
- bypass `BotPolicyService` or validation-backed review gates.
