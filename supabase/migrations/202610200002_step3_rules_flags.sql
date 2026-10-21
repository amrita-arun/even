-- Step 3: merchants, rules, flags, and jobs.
-- RLS on, no policies for anon/authenticated.

alter table public.transactions
  add column if not exists merchant_id uuid,
  add column if not exists category text;

create table public.merchants (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  display_name text not null,
  category text,
  created_at timestamptz not null default now(),
  unique (user_id, display_name)
);

alter table public.transactions
  drop constraint if exists transactions_merchant_id_fkey;

alter table public.transactions
  add constraint transactions_merchant_id_fkey
  foreign key (merchant_id) references public.merchants (id);

create table public.merchant_aliases (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  pattern text not null,
  merchant_id uuid not null references public.merchants (id),
  created_at timestamptz not null default now(),
  unique (user_id, pattern)
);

create table public.rules (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  name text not null,
  predicate jsonb not null,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.flags (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  transaction_id uuid not null references public.transactions (id),
  rule_id uuid not null references public.rules (id),
  status text not null default 'open'
    check (status in ('open', 'acknowledged', 'dismissed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (transaction_id, rule_id)
);

create table public.jobs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  job_type text not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'pending'
    check (status in ('pending', 'running', 'done', 'failed')),
  run_after timestamptz not null default now(),
  attempts int not null default 0,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index flags_user_status on public.flags (user_id, status);
create index jobs_pending on public.jobs (status, run_after)
  where status = 'pending';
create index merchants_user_id on public.merchants (user_id);
create index rules_user_id on public.rules (user_id);

do $$
declare
  tbl text;
begin
  foreach tbl in array array[
    'merchants',
    'merchant_aliases',
    'rules',
    'flags',
    'jobs'
  ]
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;
