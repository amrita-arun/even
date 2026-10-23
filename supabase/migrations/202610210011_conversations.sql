-- One open iMessage conversation per user. Approval is still the only journal write.

alter table public.users add column if not exists imessage_handle text;

create unique index if not exists users_imessage_handle_unique
  on public.users (imessage_handle)
  where imessage_handle is not null;

create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users (id),
  proposal_id uuid references public.proposals (id),
  settlement_id uuid references public.settlements (id),
  status text not null check (status in ('open', 'confirmed', 'rejected', 'expired')),
  state text not null check (state in (
    'asking_who',
    'confirming_new_person',
    'collecting_first_name',
    'collecting_last_name',
    'collecting_venmo',
    'confirming'
  )),
  pending_name text,
  pending_first_name text,
  pending_last_name text,
  pending_person_id uuid references public.people (id),
  opened_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null,
  closed_at timestamptz,
  check (num_nonnulls(proposal_id, settlement_id) = 1)
);

create unique index if not exists conversations_one_open
  on public.conversations (user_id)
  where status = 'open';

create table if not exists public.channel_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations (id),
  user_id uuid not null references public.users (id),
  direction text not null check (direction in ('inbound', 'outbound')),
  body text not null,
  external_id text,
  reply_body text,
  deliver boolean not null default false,
  sent_at timestamptz,
  created_at timestamptz not null default clock_timestamp()
);

create unique index if not exists channel_messages_external
  on public.channel_messages (user_id, external_id)
  where external_id is not null;

create index if not exists channel_messages_conversation
  on public.channel_messages (conversation_id, created_at);

do $$
declare
  tbl text;
begin
  foreach tbl in array array['conversations', 'channel_messages']
  loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('revoke all on table public.%I from anon, authenticated', tbl);
  end loop;
end $$;
