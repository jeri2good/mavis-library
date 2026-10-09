-- Mavis Library: per-user shelf, reading progress, and annotations.
-- Every table is protected by row-level security so a signed-in user can only
-- read and write rows whose user_id equals auth.uid(). Book files themselves
-- are never stored here; they stay on each device.
--
-- Conflict handling: clients send client_updated_at (ms since epoch). A
-- BEFORE UPDATE trigger keeps the existing row when an incoming write is
-- older (last-writer-wins). updated_at is set by the server clock and is the
-- cursor clients use to pull changes.

create table if not exists public.shelf_items (
  user_id uuid not null references auth.users (id) on delete cascade default auth.uid(),
  book_key text not null check (char_length(book_key) between 3 and 200),
  source text not null check (source in ('gutenberg', 'openlibrary', 'import')),
  source_id text not null default '' check (char_length(source_id) <= 120),
  title text not null check (char_length(title) <= 300),
  authors text[] not null default '{}' check (cardinality(authors) <= 8),
  cover_url text check (cover_url is null or (char_length(cover_url) <= 500 and cover_url ~ '^https://')),
  languages text[] not null default '{}' check (cardinality(languages) <= 6),
  subjects text[] not null default '{}' check (cardinality(subjects) <= 6),
  format text check (format is null or format in ('epub', 'pdf')),
  file_name text check (file_name is null or char_length(file_name) <= 255),
  file_size bigint check (file_size is null or file_size >= 0),
  status text not null default 'want' check (status in ('want', 'reading', 'finished')),
  added_at bigint,
  last_opened_at bigint,
  deleted boolean not null default false,
  client_updated_at bigint not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, book_key)
);

create table if not exists public.reading_progress (
  user_id uuid not null references auth.users (id) on delete cascade default auth.uid(),
  book_key text not null check (char_length(book_key) between 3 and 200),
  cfi text check (cfi is null or char_length(cfi) <= 2000),
  percent real check (percent is null or (percent >= 0 and percent <= 1)),
  chapter text check (chapter is null or char_length(chapter) <= 300),
  deleted boolean not null default false,
  client_updated_at bigint not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, book_key)
);

create table if not exists public.annotations (
  id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade default auth.uid(),
  book_key text not null check (char_length(book_key) between 3 and 200),
  kind text not null check (kind in ('bookmark', 'highlight', 'note')),
  cfi text not null check (char_length(cfi) <= 2000),
  text_excerpt text not null default '' check (char_length(text_excerpt) <= 2000),
  color text check (color is null or color in ('sun', 'mint', 'sky', 'rose')),
  note text not null default '' check (char_length(note) <= 5000),
  chapter text not null default '' check (char_length(chapter) <= 300),
  percent real check (percent is null or (percent >= 0 and percent <= 1)),
  created_at bigint,
  deleted boolean not null default false,
  client_updated_at bigint not null,
  updated_at timestamptz not null default now()
);

create index if not exists shelf_items_user_updated on public.shelf_items (user_id, updated_at);
create index if not exists reading_progress_user_updated on public.reading_progress (user_id, updated_at);
create index if not exists annotations_user_updated on public.annotations (user_id, updated_at);

-- Last-writer-wins + server timestamps. Also pins user_id so a row can never
-- be moved to another account.
create or replace function public.mavis_lww() returns trigger
language plpgsql set search_path = public as $$
begin
  if tg_op = 'UPDATE' then
    if new.user_id is distinct from old.user_id then
      raise exception 'user_id cannot change';
    end if;
    if new.client_updated_at < old.client_updated_at then
      return null; -- incoming write is older: keep what we have
    end if;
  end if;
  new.updated_at := clock_timestamp();
  return new;
end $$;

drop trigger if exists shelf_items_lww on public.shelf_items;
create trigger shelf_items_lww before insert or update on public.shelf_items
  for each row execute function public.mavis_lww();
drop trigger if exists reading_progress_lww on public.reading_progress;
create trigger reading_progress_lww before insert or update on public.reading_progress
  for each row execute function public.mavis_lww();
drop trigger if exists annotations_lww on public.annotations;
create trigger annotations_lww before insert or update on public.annotations
  for each row execute function public.mavis_lww();

alter table public.shelf_items enable row level security;
alter table public.reading_progress enable row level security;
alter table public.annotations enable row level security;

-- Owner-only policies.
do $$
declare t text;
begin
  foreach t in array array['shelf_items', 'reading_progress', 'annotations'] loop
    execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_delete_own', t);
    execute format('create policy %I on public.%I for select to authenticated using ((select auth.uid()) = user_id)', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check ((select auth.uid()) = user_id)', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using ((select auth.uid()) = user_id)', t || '_delete_own', t);
  end loop;
end $$;

-- Anonymous visitors get nothing; signed-in users get only what RLS allows.
revoke all on public.shelf_items, public.reading_progress, public.annotations from anon;
grant select, insert, update, delete on public.shelf_items, public.reading_progress, public.annotations to authenticated;
