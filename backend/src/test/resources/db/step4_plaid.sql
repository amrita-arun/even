-- Step 4: Plaid items (encrypted access tokens) and account link.

create table public.plaid_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  item_id text not null,
  access_token_encrypted text not null,
  cursor text,
  institution_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (item_id),
  unique (user_id, item_id)
);

alter table public.accounts
  add column if not exists plaid_item_id uuid references public.plaid_items (id);

create index plaid_items_user_id on public.plaid_items (user_id);

do $$
begin
  execute 'alter table public.plaid_items enable row level security';
  execute 'revoke all on table public.plaid_items from anon, authenticated';
end $$;
