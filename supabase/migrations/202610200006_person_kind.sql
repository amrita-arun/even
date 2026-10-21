-- Friend vs club. A club receivable is a reimbursement, not a personal split.

alter table public.people
  add column if not exists kind text not null default 'friend';

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'people_kind_check'
  ) then
    alter table public.people
      add constraint people_kind_check check (kind in ('friend', 'club'));
  end if;
end $$;
