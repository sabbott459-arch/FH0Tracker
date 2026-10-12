-- FH0Tracker stage 2: PIN access RPCs, scoped sessions, and cloud character saves.
-- Run AFTER setup.sql. No Supabase Auth accounts are required.
-- PINs are hashed with pgcrypto; sessions use random bearer tokens.
-- For a casual game: rate limits are deliberately simple.
create extension if not exists pgcrypto;

create table if not exists public.fh_sessions (
 id uuid primary key default gen_random_uuid(),
 access_id uuid not null references public.fh_access(id) on delete cascade,
 token_hash text not null unique,
 expires_at timestamptz not null default (now() + interval '90 days'),
 created_at timestamptz not null default now()
);
create table if not exists public.fh_login_attempts (
 campaign_code text not null,
 attempted_at timestamptz not null default now()
);
create index if not exists fh_login_attempts_code_time on public.fh_login_attempts(campaign_code, attempted_at);
alter table public.fh_sessions enable row level security;
alter table public.fh_login_attempts enable row level security;
revoke all on public.fh_sessions, public.fh_login_attempts from public, anon, authenticated;

-- Internal helper: caller identity from a bearer token (never expose token hashes).
create or replace function public.fh_actor(p_token text)
returns table(campaign_id uuid, role text, character_id uuid, access_id uuid)
language sql stable security definer set search_path = ''
as $fh$
 select a.campaign_id,a.role,a.character_id,a.id
 from public.fh_sessions s join public.fh_access a on a.id=s.access_id
 where s.token_hash=encode(extensions.digest(p_token,'sha256'),'hex')
   and s.expires_at>now() and length(p_token)=64
 limit 1
$fh$;
revoke all on function public.fh_actor(text) from public,anon,authenticated;

create or replace function public.fh_create_campaign(p_name text,p_manager_pin text,p_character_name text,p_character_pin text,p_data jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $fh$
declare v_campaign uuid;v_code text;v_character uuid;v_access uuid;v_token text;v_try int;
begin
 if p_manager_pin !~ '^[0-9]{4}$' or p_character_pin !~ '^[0-9]{4}$' or p_manager_pin=p_character_pin then raise exception 'Choose two different 4-digit PINs';end if;
 if length(trim(coalesce(p_name,''))) not between 1 and 80 or length(trim(coalesce(p_character_name,''))) not between 1 and 70 then raise exception 'Enter a campaign and character name';end if;
 for v_try in 1..10 loop
  v_code:='FROST-'||upper(substr(encode(extensions.gen_random_bytes(6),'hex'),1,8));
  begin
   insert into public.fh_campaigns(code,name) values(v_code,trim(p_name)) returning id into v_campaign;
   exit;
  exception when unique_violation then
   if v_try=10 then raise;end if;
  end;
 end loop;
 insert into public.fh_characters(campaign_id,name,class_id,data) values(v_campaign,trim(p_character_name),'fist',coalesce(p_data,'{}'::jsonb)) returning id into v_character;
 insert into public.fh_access(campaign_id,role,pin_hash) values(v_campaign,'manager',extensions.crypt(p_manager_pin,extensions.gen_salt('bf',10))) returning id into v_access;
 insert into public.fh_access(campaign_id,role,pin_hash,character_id) values(v_campaign,'character',extensions.crypt(p_character_pin,extensions.gen_salt('bf',10)),v_character);
 v_token:=encode(extensions.gen_random_bytes(32),'hex');
 insert into public.fh_sessions(access_id,token_hash) values(v_access,encode(extensions.digest(v_token,'sha256'),'hex'));
 return jsonb_build_object('code',v_code,'token',v_token,'role','manager','character_id',v_character);
end
$fh$;

create or replace function public.fh_login(p_code text,p_pin text)
returns jsonb language plpgsql security definer set search_path = ''
as $fh$
declare v_access record;v_token text;v_count int;
begin
 if p_code !~ '^FROST-[A-Z0-9]{8}$' or p_pin !~ '^[0-9]{4}$' then raise exception 'Invalid campaign code or PIN';end if;
 select count(*) into v_count from public.fh_login_attempts where campaign_code=p_code and attempted_at>now()-interval '10 minutes';
 if v_count>=30 then return jsonb_build_object('error','Too many attempts; try again later');end if;
 insert into public.fh_login_attempts(campaign_code) values(p_code);
 select a.id,a.role,a.character_id into v_access
 from public.fh_access a join public.fh_campaigns c on c.id=a.campaign_id
 where c.code=p_code and a.pin_hash=extensions.crypt(p_pin,a.pin_hash) limit 1;
 if not found then return jsonb_build_object('error','Invalid campaign code or PIN');end if;
 v_token:=encode(extensions.gen_random_bytes(32),'hex');
 insert into public.fh_sessions(access_id,token_hash) values(v_access,encode(extensions.digest(v_token,'sha256'),'hex'));
 return jsonb_build_object('token',v_token,'role',v_access.role,'character_id',v_access.character_id);
end
$fh$;

create or replace function public.fh_dashboard(p_token text)
returns jsonb language plpgsql security definer set search_path = ''
as $fh$
declare v_actor record;v_code text;v_name text;v_chars jsonb;
begin
 select * into v_actor from public.fh_actor(p_token);
 if not found then raise exception 'Session expired; log in again';end if;
 select code,name into v_code,v_name from public.fh_campaigns where id=v_actor.campaign_id;
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'classId',class_id,'data',data,'revision',revision) order by updated_at desc),'[]'::jsonb) into v_chars
 from public.fh_characters where campaign_id=v_actor.campaign_id and (v_actor.role='manager' or id=v_actor.character_id);
 return jsonb_build_object('code',v_code,'name',v_name,'role',v_actor.role,'characters',v_chars);
end
$fh$;

create or replace function public.fh_save_character(p_token text,p_character_id uuid,p_revision bigint,p_data jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $fh$
declare v_actor record;v_revision bigint;v_name text;
begin
 select * into v_actor from public.fh_actor(p_token);
 if not found then raise exception 'Session expired; log in again';end if;
 if v_actor.role<>'manager' and v_actor.character_id is distinct from p_character_id then raise exception 'Access denied';end if;
 if pg_catalog.pg_column_size(p_data)>100000 then raise exception 'Character data too large';end if;
 select revision into v_revision from public.fh_characters where id=p_character_id and campaign_id=v_actor.campaign_id for update;
 if not found then raise exception 'Character not found';end if;
 if v_revision<>p_revision then raise exception 'Save conflict: reload character before editing';end if;
 insert into public.fh_snapshots(character_id,data,revision)
 select id,data,revision from public.fh_characters where id=p_character_id;
 v_name:=left(coalesce(nullif(p_data->>'name',''),'Unnamed'),70);
 update public.fh_characters set data=p_data,name=v_name,revision=revision+1,updated_at=now() where id=p_character_id;
 return jsonb_build_object('revision',v_revision+1);
end
$fh$;

create or replace function public.fh_add_character(p_token text,p_name text,p_pin text,p_data jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $fh$
declare v_actor record;v_id uuid;v_n int;
begin
 select * into v_actor from public.fh_actor(p_token);
 if not found or v_actor.role<>'manager' then raise exception 'Manager PIN required';end if;
 if p_pin !~ '^[0-9]{4}$' or length(trim(coalesce(p_name,''))) not between 1 and 70 then raise exception 'Enter a name and 4-digit PIN';end if;
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(v_actor.campaign_id::text));
 select count(*) into v_n from public.fh_characters where campaign_id=v_actor.campaign_id;
 if v_n>=4 then raise exception 'Campaign already has four characters';end if;
 if exists(select 1 from public.fh_access where campaign_id=v_actor.campaign_id and pin_hash=extensions.crypt(p_pin,pin_hash)) then raise exception 'PIN already in use';end if;
 insert into public.fh_characters(campaign_id,name,class_id,data) values(v_actor.campaign_id,trim(p_name),'fist',coalesce(p_data,'{}'::jsonb)) returning id into v_id;
 insert into public.fh_access(campaign_id,role,pin_hash,character_id) values(v_actor.campaign_id,'character',extensions.crypt(p_pin,extensions.gen_salt('bf',10)),v_id);
 return jsonb_build_object('character_id',v_id);
end
$fh$;

create or replace function public.fh_logout(p_token text)
returns boolean language plpgsql security definer set search_path = ''
as $fh$
begin
 delete from public.fh_sessions where token_hash=encode(extensions.digest(p_token,'sha256'),'hex');
 return found;
end
$fh$;

-- Never expose the internal actor function. Only grant the public RPC entry points.
revoke all on function public.fh_create_campaign(text,text,text,text,jsonb) from public;
revoke all on function public.fh_login(text,text) from public;
revoke all on function public.fh_dashboard(text) from public;
revoke all on function public.fh_save_character(text,uuid,bigint,jsonb) from public;
revoke all on function public.fh_add_character(text,text,text,jsonb) from public;
revoke all on function public.fh_logout(text) from public;
grant execute on function public.fh_create_campaign(text,text,text,text,jsonb) to anon,authenticated;
grant execute on function public.fh_login(text,text) to anon,authenticated;
grant execute on function public.fh_dashboard(text) to anon,authenticated;
grant execute on function public.fh_save_character(text,uuid,bigint,jsonb) to anon,authenticated;
grant execute on function public.fh_add_character(text,text,text,jsonb) to anon,authenticated;
grant execute on function public.fh_logout(text) to anon,authenticated;
