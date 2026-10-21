-- Broaden club into any full payback (work, club, or anyone else).
-- Drop the old check before rewriting rows; Postgres validates the new value immediately.

alter table public.people drop constraint if exists people_kind_check;

update public.people
set kind = 'reimbursement'
where kind = 'club';

alter table public.people
  add constraint people_kind_check check (kind in ('friend', 'reimbursement'));
