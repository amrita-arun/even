-- Step 2: people, splits, and split shares.
-- RLS on, no policies for anon/authenticated. Spring connects as owner.

create table public.people (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  display_name text not null,
  created_at timestamptz not null default now(),
  unique (user_id, display_name)
);

create table public.splits (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  transaction_id uuid not null references public.transactions (id),
  method text not null check (method in ('equal', 'percentage', 'fixed', 'itemized')),
  tax_cents bigint not null default 0 check (tax_cents >= 0),
  tip_cents bigint not null default 0 check (tip_cents >= 0),
  created_at timestamptz not null default now(),
  unique (transaction_id)
);

create table public.split_shares (
  id uuid primary key default gen_random_uuid(),
  split_id uuid not null references public.splits (id) on delete cascade,
  person_id uuid references public.people (id),
  share_cents bigint not null check (share_cents >= 0),
  created_at timestamptz not null default now()
);

-- One owner share (person_id null) and at most one share per friend.
create unique index split_shares_owner_key
  on public.split_shares (split_id)
  where person_id is null;

create unique index split_shares_person_key
  on public.split_shares (split_id, person_id)
  where person_id is not null;

create index splits_user_id on public.splits (user_id);
create index people_user_id on public.people (user_id);

do $$
declare
  tbl text;
begin
  foreach tbl in array array['people', 'splits', 'split_shares']
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;
