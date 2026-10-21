-- First and last name so a Venmo request is never aimed at a first name alone.

alter table public.people
  add column if not exists first_name text,
  add column if not exists last_name text;
