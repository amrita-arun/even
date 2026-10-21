-- Pending split suggestions. Approval is what posts to the journal.

create table public.proposals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  transaction_id uuid not null references public.transactions (id),
  status text not null check (status in ('pending', 'approved', 'rejected')),
  score int not null check (score >= 0 and score <= 100),
  reason text not null,
  person_ids jsonb not null,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  unique (transaction_id)
);

create table public.split_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  proposal_id uuid not null references public.proposals (id),
  transaction_id uuid not null references public.transactions (id),
  action text not null check (action in ('reject', 'edit')),
  person_ids jsonb,
  created_at timestamptz not null default now()
);

create table public.ai_audit_log (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  transaction_id uuid references public.transactions (id),
  model text not null,
  request jsonb not null,
  response jsonb not null,
  created_at timestamptz not null default now()
);

create index proposals_user_status on public.proposals (user_id, status);
create index split_feedback_proposal on public.split_feedback (proposal_id);
create index ai_audit_log_user on public.ai_audit_log (user_id);

do $$
declare
  tbl text;
begin
  foreach tbl in array array['proposals', 'split_feedback', 'ai_audit_log']
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;
