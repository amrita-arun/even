-- Step 1 core schema. RLS is on and no policies are created, so the
-- publishable key cannot read or write these tables via the Data API.
-- Spring connects as the table owner and bypasses RLS.

do $$
begin
  if not exists (select from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
end $$;

create table public.users (
  id uuid primary key,
  email text not null,
  created_at timestamptz not null default now()
);

create table public.institutions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  name text not null,
  provider text not null,
  created_at timestamptz not null default now()
);

create table public.accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  institution_id uuid not null references public.institutions (id),
  name text not null,
  account_type text not null check (account_type in ('credit', 'depository')),
  currency char(3) not null default 'USD',
  provider text not null default 'manual',
  external_account_id text,
  created_at timestamptz not null default now()
);

create unique index accounts_external_id_key
  on public.accounts (user_id, provider, external_account_id)
  where external_account_id is not null;

create table public.transactions (
  id uuid primary key default gen_random_uuid(),
  account_id uuid not null references public.accounts (id),
  user_id uuid not null references public.users (id),
  amount_cents bigint not null,
  currency char(3) not null,
  posted_on date not null,
  status text not null check (status in ('pending', 'posted', 'removed')),
  description text not null,
  kind text not null check (kind in ('purchase', 'payment', 'refund')),
  raw_payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create or replace function public.transactions_set_user_id()
returns trigger
language plpgsql
as $$
declare
  owner uuid;
begin
  select user_id into owner from public.accounts where id = new.account_id;
  if owner is null then
    raise exception 'account % not found', new.account_id;
  end if;
  if new.user_id is null then
    new.user_id := owner;
  elsif new.user_id <> owner then
    raise exception 'transaction user_id does not match account owner';
  end if;
  return new;
end;
$$;

create trigger transactions_user_id
before insert or update of account_id, user_id on public.transactions
for each row execute function public.transactions_set_user_id();

create index transactions_user_posted_on on public.transactions (user_id, posted_on);
create index transactions_account_id on public.transactions (account_id);

create table public.transaction_external_ids (
  account_id uuid not null references public.accounts (id),
  external_id text not null,
  transaction_id uuid not null references public.transactions (id),
  primary key (account_id, external_id)
);

create index transaction_external_ids_transaction_id
  on public.transaction_external_ids (transaction_id);

create table public.ledger_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  path text not null,
  unique (user_id, path)
);

create table public.ledger_postings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  txn_group_id uuid not null unique,
  source_transaction_id uuid not null references public.transactions (id),
  purpose text not null check (
    purpose in ('capture', 'card_payment', 'refund', 'reversal', 'split', 'settlement')
  ),
  created_at timestamptz not null default now(),
  unique (source_transaction_id, purpose)
);

create table public.ledger_entries (
  id uuid primary key default gen_random_uuid(),
  txn_group_id uuid not null references public.ledger_postings (txn_group_id),
  ledger_account_id uuid not null references public.ledger_accounts (id),
  amount_cents bigint not null,
  source_transaction_id uuid not null references public.transactions (id),
  created_at timestamptz not null default now()
);

create index ledger_entries_txn_group_id on public.ledger_entries (txn_group_id);

create or replace function public.reject_ledger_entry_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception 'ledger_entries are append-only';
end;
$$;

create trigger ledger_entries_no_update
before update or delete on public.ledger_entries
for each row execute function public.reject_ledger_entry_mutation();

create or replace function public.assert_ledger_group_sums_to_zero()
returns trigger
language plpgsql
as $$
declare
  total bigint;
begin
  select coalesce(sum(amount_cents), 0)
    into total
  from public.ledger_entries
  where txn_group_id = new.txn_group_id;

  if total <> 0 then
    raise exception 'ledger group % sums to %', new.txn_group_id, total;
  end if;
  return null;
end;
$$;

create constraint trigger ledger_entries_balanced
after insert on public.ledger_entries
deferrable initially deferred
for each row
execute function public.assert_ledger_group_sums_to_zero();

do $$
declare
  tbl text;
begin
  foreach tbl in array array[
    'users',
    'institutions',
    'accounts',
    'transactions',
    'transaction_external_ids',
    'ledger_accounts',
    'ledger_postings',
    'ledger_entries'
  ]
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;

revoke all on function public.transactions_set_user_id() from public, anon, authenticated;
revoke all on function public.reject_ledger_entry_mutation() from public, anon, authenticated;
revoke all on function public.assert_ledger_group_sums_to_zero() from public, anon, authenticated;
