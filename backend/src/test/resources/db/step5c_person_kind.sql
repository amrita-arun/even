-- Friend vs reimbursement. A reimbursement owes the whole charge back.

alter table public.people
  add column if not exists kind text not null default 'friend';

alter table public.people drop constraint if exists people_kind_check;

update public.people
set kind = 'reimbursement'
where kind = 'club';

alter table public.people
  add constraint people_kind_check check (kind in ('friend', 'reimbursement'));
