-- Step 5: settlement handles on people + pending/confirmed settlements.

alter table public.people
  add column if not exists venmo_handle text,
  add column if not exists zelle_handle text;

create table public.settlements (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  person_id uuid not null references public.people (id),
  amount_cents bigint not null check (amount_cents > 0),
  status text not null check (status in ('suggested', 'confirmed', 'rejected')),
  source_transaction_id uuid references public.transactions (id),
  channel text not null check (channel in ('venmo', 'zelle', 'manual', 'deposit_match')),
  draft_text text,
  created_at timestamptz not null default now(),
  confirmed_at timestamptz,
  unique (source_transaction_id)
);

create index settlements_user_id on public.settlements (user_id);
create index settlements_person_id on public.settlements (person_id);
create index settlements_status on public.settlements (user_id, status);

do $$
begin
  execute 'alter table public.settlements enable row level security';
  execute 'revoke all on table public.settlements from anon, authenticated';
end $$;
