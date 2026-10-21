-- Recurring split suggestions. Saving a rule does not post a journal entry.

create table public.person_groups (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  name text not null,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.person_group_members (
  group_id uuid not null references public.person_groups (id) on delete cascade,
  person_id uuid not null references public.people (id) on delete cascade,
  primary key (group_id, person_id)
);

create table public.split_rules (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  name text not null,
  merchant_match text not null,
  method text not null check (method in ('equal', 'percentage')),
  person_id uuid references public.people (id) on delete cascade,
  group_id uuid references public.person_groups (id) on delete cascade,
  basis_points int check (basis_points is null or (basis_points > 0 and basis_points < 10000)),
  created_at timestamptz not null default now(),
  check (num_nonnulls(person_id, group_id) = 1),
  check (
    (method = 'equal' and basis_points is null)
    or (method = 'percentage' and basis_points is not null)
  )
);

create index split_rules_user on public.split_rules (user_id);

do $$
declare
  tbl text;
begin
  foreach tbl in array array['person_groups', 'person_group_members', 'split_rules']
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;
