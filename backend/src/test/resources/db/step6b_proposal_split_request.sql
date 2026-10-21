-- Language and receipt proposals store the validated split. Approval posts it.

alter table public.proposals
  add column if not exists split_request jsonb;
