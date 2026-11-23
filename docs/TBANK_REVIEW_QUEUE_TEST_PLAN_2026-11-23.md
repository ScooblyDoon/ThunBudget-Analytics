# T-Bank notification review queue — test plan (2026-11-23)

## Scope and safety boundary

This plan defines the first-stage behavior for T-Bank notification import in ThunBudget Analytics.

- Work only in `feature/tbank-smart-import-lab-1.3.25`.
- Do not modify `main` or prepare a release.
- Do not register or enable `NotificationListenerService` yet.
- Do not write parsed notifications into the financial ledger.
- Do not change the existing manual text/voice entry flow.
- Physical device validation is deferred; when approved, use Google Pixel 8 only.

The current parser remains a pure Kotlin component. Parsed events are candidates, not budget operations.

## Target user flow

1. Android receives a notification from the trusted T-Bank package.
2. The notification is normalized and parsed locally.
3. A preliminary operation is appended to a persistent local review queue.
4. The user opens ThunBudget later and reviews each pending item independently.
5. The user can confirm, edit and confirm, or reject an item.
6. Only a confirmed item may be handed to the ledger adapter in a later, separately approved integration stage.

Pending and rejected items must never affect budget totals, reporting periods, balances, or analytics.

## Queue record contract

Each queued item should preserve the minimum information required for review and safe deduplication:

- local immutable queue ID;
- source package (T-Bank only);
- source notification key and bank reference ID when available;
- parsed operation type and direction;
- amount in integer kopecks, or null when ambiguous;
- currency;
- merchant/payee label, if recognized;
- suggested category, if applicable;
- notification observation timestamp (not represented as the bank transaction date);
- confidence and review reasons;
- minimal source payload needed for user inspection, with a retention/deletion policy;
- status: PENDING, CONFIRMED, REJECTED;
- user-edited values and decision timestamp when applicable.

The queue must not assume that notification post time is the transaction date. It must not deduplicate merely by merchant + amount.

## Required test scenarios

### A. Accumulation and ordering
- Receive three different purchase notifications before the app is opened: all three appear as separate pending items.
- Receive multiple notifications over several hours: all pending items remain available.
- Receive notifications in quick succession: no item is overwritten by the next event.
- Same merchant and same amount on two separate purchases: keep both unless a stable source identifier proves they are the same notification event.
- A notification update with the same stable key: update/replace the same queue item rather than append a duplicate, subject to verified platform semantics.

### B. Persistence and offline behavior
- Pending items survive process death.
- Pending items survive app relaunch.
- Pending items survive device reboot.
- Queue read/write works without internet access.
- A partially written or malformed persisted record does not erase other valid pending records.
- Storage migration preserves pending items when the queue schema version changes.

### C. Individual review actions
- Confirm one item: only that item changes state.
- Edit amount, category, merchant label, or operation direction and confirm: the edited values are the values passed to the future ledger adapter.
- Reject one item: other pending items remain unchanged.
- Reopen the queue: confirmed/rejected items are not shown as pending.
- Repeated confirm tap or replayed callback: no duplicate ledger handoff.
- Editing an item must not alter the original source identity used for deduplication.

### D. Financial isolation
- Pending expense does not affect expense totals, remaining budget, average daily spending, or charts.
- Pending income does not affect income totals.
- Internal transfer remains excluded from income and expense unless the user explicitly resolves an ambiguous case.
- Refund/reversal stays in review and is not silently recorded as ordinary income.
- Cashback information does not change the purchase amount or create a duplicate purchase.
- Rejected item never reaches the ledger.
- A confirmed item is handed off once only; undo/reversal behavior must be specified before production integration.

### E. Parser regression coverage
- Purchase amount remains correct when cashback/balance text appears before or after the transaction amount.
- Amounts with kopecks, grouped thousands, non-breaking spaces, and RUB/₽ formats.
- Merchant in title, text, or bigText.
- Incoming/outgoing/own-account transfers.
- Cash withdrawal vs. a purchase whose category label is “Наличные”.
- Refund, reversal, cashback, balance notice, subscription, unknown and ambiguous messages.
- Empty, truncated, expanded, collapsed, and duplicated notification payloads.

## Test execution stages

1. Expand pure parser unit tests and fix parser regressions.
2. Implement/test a local queue repository in isolation, without a listener and without ledger access.
3. Test queue persistence, state transitions, deduplication, and restart recovery.
4. Add a fake notification source for deterministic CI scenarios.
5. Only after virtual checks pass, request separate approval for listener integration and Pixel 8 testing.

## Acceptance criteria for this stage

- Multiple separate notifications accumulate without overwriting one another.
- Queue contents survive app/process restart and offline use.
- Every pending item can be independently edited, confirmed, or rejected.
- No pending/rejected item changes financial totals.
- Duplicate delivery does not create duplicate pending records when a stable notification identity exists.
- Same merchant + same amount does not cause false deduplication.
- Existing manual voice/text entry behavior remains untouched.
- CI tests pass on the lab branch.

## Current implementation status

This file is a test plan, not evidence that the queue or listener has been implemented. The existing parser is isolated and does not persist events or modify the budget ledger.
