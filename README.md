# Even

Personal finance: sync card and bank transactions, flag large purchases, split shared expenses, and report true spend from a double-entry ledger.

## Stack

- **Backend:** Java 21, Spring Boot, JDBC
- **DB / Auth:** Supabase Postgres + Supabase Auth
- **Frontend:** Vite + React

## Checkpoint 1

Sign in with Supabase Auth. Spring validates the access token and `GET /api/auth/me` upserts your `users` row.

## Ledger + CSV import

`LedgerService` appends balanced journal groups (debit positive, credit negative). Capture of a posted purchase debits `Expenses:Uncategorized` and credits `Liabilities:{accountId}`. Card payments move liability to `Assets:Checking` and never touch expenses. Groups that do not sum to zero are rejected by a deferred database trigger; `ledger_entries` are append-only.

After sign-in: create a credit account, upload a **Capital One print-transactions PDF** (or a CSV), and open a transaction to see its journal lines. Capital One only exports PDF from the website; `CapitalOnePdfConnector` parses that Chrome print layout into the same ingest path as CSV. Re-importing does not double-post (`CsvImportIdempotencyTest`, `CapitalOnePdfImportTest`). Pending→posted linking is covered by `PendingToPostedTest` and `PlaidSyncPageMapperTest`.

### Splits and true spend

Apply [`supabase/migrations/202610200001_step2_splits.sql`](supabase/migrations/202610200001_step2_splits.sql) in the Supabase SQL Editor (once). Add people, open a posted purchase, and split equally with friends. The adjustment credits `Expenses` for their shares and debits `Receivable:{person}`; true spend is `sum(Expenses:*)` (`DinnerSplitIntegrationTest`).

### Rules and flags

Apply [`supabase/migrations/202610200002_step3_rules_flags.sql`](supabase/migrations/202610200002_step3_rules_flags.sql) once. After each import, a job enriches the merchant/category and evaluates seeded rules (restaurant over $60, rideshare over $40). Flags are unique on `(transaction_id, rule_id)` so re-runs do not duplicate (`RuleFlagIdempotencyTest`).

### Plaid

Apply [`supabase/migrations/202610200003_step4_plaid.sql`](supabase/migrations/202610200003_step4_plaid.sql) once. Default is fixture mode (`PLAID_LIVE=false`): **Continue with demo bank** stores an encrypted access token and replays the checked-in sync pages through the same ingest path. Pending→posted tip matching allows up to a $25 amount delta when `pending_transaction_id` is missing (`TipTolerancePendingMatchTest`, `PlaidSyncReplayTest`). Set `PLAID_CLIENT_ID`, `PLAID_SECRET`, and `PLAID_LIVE=true` (sandbox by default) and restart the API: Connect then opens Plaid Link, stores the institution name, and syncs each selected checking or credit account. Sandbox sign-in is `user_good` / `pass_good`. OAuth institutions also need `PLAID_REDIRECT_URI` registered in the Plaid dashboard.

### Settlement

Apply [`supabase/migrations/202610200004_step5_settlement.sql`](supabase/migrations/202610200004_step5_settlement.sql) and [`supabase/migrations/202610200005_person_names.sql`](supabase/migrations/202610200005_person_names.sql) once. From an open receivable: **Venmo** opens a charge deep link only when that person has a saved username (a first name is never used as `recipients`), **Zelle** copies reminder text, **Mark paid** clears the receivable with an `Assets:Checking` / `Receivable:{person}` settlement posting. **Find deposit matches** suggests checking inflows by amount + name within 30 days; confirm posts the same settlement group (`SettlementClearTest`). People are stored as first name + last name. The later iMessage agent must ask for both, plus the Venmo username, before it drafts a request. A person is either a friend or a reimbursement: **Split equally** divides a purchase with friends, and **They owe this** moves the whole charge off true spend onto that reimbursement until you mark it paid back.

### Prerequisites

- Java 21
- Node 22+
- Docker (for backend tests via Testcontainers)
- A Supabase project with one Auth user

### Configure

```bash
cp .env.example .env
# fill SUPABASE_* and VITE_* values from the Supabase Connect dialog
```

Required in `.env`:

- `SUPABASE_URL` — same as `VITE_SUPABASE_URL` (used as the JWT issuer base)
- `SUPABASE_JWKS_URL` — `https://<ref>.supabase.co/auth/v1/.well-known/jwks.json`
- DB vars + `VITE_SUPABASE_ANON_KEY`

If sign-in works but `/api/auth/me` returns **401**, check the backend log for `JWT rejected`. Newer Supabase projects sign with **ES256** via JWKS; this app enables ES256 on the JWKS decoder. Only set `SUPABASE_JWT_SECRET` (and leave `SUPABASE_JWKS_URL` empty) if you are intentionally on the legacy HS256 JWT Secret.

Apply the schema once in the Supabase **SQL Editor** by pasting
[`supabase/migrations/202609280001_step1_core.sql`](supabase/migrations/202609280001_step1_core.sql),
or with the Supabase CLI: `supabase db push`.

### Run

```bash
# terminal 1 — load env then start API
set -a && source .env && set +a
cd backend && ./mvnw spring-boot:run

# terminal 2
cd frontend
cp ../.env .env   # or symlink VITE_* vars
npm run dev
```

Open http://localhost:5173 and sign in with your Supabase Auth user. **Load demo** adds a sandbox card (dinner split, Uber flag, work-trip reimbursement) without touching accounts you already imported. The client is installable from `frontend/public/manifest.webmanifest`.

### Proposals

Apply [`supabase/migrations/202610200008_proposals.sql`](supabase/migrations/202610200008_proposals.sql) once. After a transaction is processed, a heuristic (category, amount, open flag, and how often that merchant was split) may write a **pending** proposal. **Approve** runs the equal split. **Reject** writes feedback and does not post. A second approve does not post again. Every suggestion writes `ai_audit_log` with the mock model `heuristic-mock` (`ProposalFlowTest`, `SplitSuggestionScorerTest`).

### Language, receipts, and reminders

`POST /api/splits/parse` and `POST /api/receipts` turn a sentence or receipt lines into a **pending** proposal. Shares are checked with the same split engine as a manual split. If they do not add up to the charge, nothing is proposed and nothing is posted. **Approve** posts that split. Reminder drafts (`POST /api/reminders`) are kept only when every dollar amount matches the open receivable; otherwise the template is used. Instruction-shaped lines are dropped. Merchant names and memos are stored as data on `ai_audit_log` (`language-mock`, `LanguageSplitFlowTest`). Apply [`supabase/migrations/202610200009_proposal_split_request.sql`](supabase/migrations/202610200009_proposal_split_request.sql) once.

### Tests

```bash
cd backend && ./mvnw test
cd frontend && npm run build
```

## Design notes (so far)

- **Integer cents** — money is never a floating-point type in the database or API.
- **Auth** — the browser signs in with Supabase; Spring is an OAuth2 resource server that trusts the project's JWKS. Per-user isolation is enforced in Spring queries.
- **Schema** — public tables enable RLS with no policies for `anon` / `authenticated`, so the publishable key cannot read the ledger via the Data API.
