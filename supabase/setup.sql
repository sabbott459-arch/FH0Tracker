-- FH0Tracker v0.3 initial cloud schema.
-- Run in Supabase SQL Editor. All client access is denied until secure RPCs are installed.
create extension if not exists pgcrypto;

create table if not exists public.fh_campaigns (
 id uuid primary key default gen_random_uuid(),
 code text not null unique check (code ~ '^FROST-[A-Z0-9]{8}$'),
 name text not null default 'Frosthaven Campaign',
 created_at timestamptz not null default now()
);
create table if not exists public.fh_access (
 id uuid primary key default gen_random_uuid(),
 campaign_id uuid not null references public.fh_campaigns(id) on delete cascade,
 role text not null check (role in ('manager','character')),
 pin_hash text not null,
 character_id uuid,
 created_at timestamptz not null default now()
);
create unique index if not exists fh_one_manager on public.fh_access(campaign_id) where role='manager';
create table if not exists public.fh_characters (
 id uuid primary key default gen_random_uuid(),
 campaign_id uuid not null references public.fh_campaigns(id) on delete cascade,
 name text not null,
 class_id text not null default 'fist',
 data jsonb not null default '{}'::jsonb,
 revision bigint not null default 0,
 updated_at timestamptz not null default now()
);
-- Character FK will be installed in a later migration after auth is ready.
create unique index if not exists fh_one_access_per_character on public.fh_access(character_id) where character_id is not null;
create table if not exists public.fh_snapshots (
 id bigint generated always as identity primary key,
 character_id uuid not null references public.fh_characters(id) on delete cascade,
 data jsonb not null,
 revision bigint not null,
 created_at timestamptz not null default now()
);

-- Lock down all direct browser access. RPC functions will be added in the next stage.
alter table public.fh_campaigns enable row level security;
alter table public.fh_access enable row level security;
alter table public.fh_characters enable row level security;
alter table public.fh_snapshots enable row level security;
revoke all on public.fh_campaigns, public.fh_access, public.fh_characters, public.fh_snapshots from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;

-- Do not create open read/write policies: they would expose other players' data.
-- Do not store raw PINs in any of these tables.
