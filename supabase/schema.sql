-- Run once in a dedicated Supabase project. Disable public signup in Auth settings.
create table if not exists public.approved_viewers (
 user_id uuid primary key references auth.users(id) on delete cascade
);
alter table public.approved_viewers enable row level security;
-- No browser policies: only the service role / SQL administrator manages membership.
create table if not exists public.published_snapshots (
 id integer primary key check (id = 1),
 payload jsonb not null,
 published_at timestamptz not null default now()
);
alter table public.published_snapshots enable row level security;
create or replace function public.is_approved_viewer() returns boolean
 language sql stable security definer set search_path = public
 as $$ select exists(select 1 from public.approved_viewers where user_id = auth.uid()) $$;
revoke all on function public.is_approved_viewer() from public, anon;
grant execute on function public.is_approved_viewer() to authenticated;
drop policy if exists "approved users read only" on public.published_snapshots;
create policy "approved users read only" on public.published_snapshots
 for select to authenticated using (public.is_approved_viewer());
revoke all on public.approved_viewers from anon, authenticated;
revoke all on public.published_snapshots from anon, authenticated;
grant select on public.published_snapshots to authenticated;
create or replace function public.publish_snapshot(snapshot_payload jsonb) returns void
 language plpgsql security definer set search_path = public as $$
 begin
 if jsonb_typeof(snapshot_payload) <> 'object' or snapshot_payload->>'version' <> '1'
 then raise exception 'invalid snapshot'; end if;
 insert into public.published_snapshots(id,payload,published_at) values(1,snapshot_payload,now())
 on conflict(id) do update set payload=excluded.payload,published_at=excluded.published_at;
 end; $$;
revoke all on function public.publish_snapshot(jsonb) from public,anon,authenticated;
grant execute on function public.publish_snapshot(jsonb) to service_role;
