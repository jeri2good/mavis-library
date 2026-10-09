// Runs the real Supabase migration inside PGlite (Postgres compiled to WASM)
// with a stand-in for Supabase's auth schema, then checks that row-level
// security isolates users and that last-writer-wins behaves.
//
//   npm run test:db

import { PGlite } from '@electric-sql/pglite';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const db = new PGlite();
const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
let passed = 0;
const ok = (name) => { passed++; console.log(`  ✓ ${name}`); };

// --- Supabase auth stand-in ---
await db.exec(`
  create schema auth;
  create table auth.users (id uuid primary key);
  create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
  create role anon nologin;
  create role authenticated nologin;
  grant usage on schema public to anon, authenticated;
  grant usage on schema auth to anon, authenticated;
  grant execute on function auth.uid() to anon, authenticated;
  insert into auth.users values ('${A}'), ('${B}');
`);
await db.exec(readFileSync(new URL('../../supabase/migrations/0001_mavis_library.sql', import.meta.url), 'utf8'));
await db.exec(readFileSync(new URL('../../supabase/migrations/0002_bible_and_quotes.sql', import.meta.url), 'utf8'));
ok('migrations apply cleanly');

async function as(user, sql, params = []) {
  await db.exec(`reset role; select set_config('request.jwt.claim.sub', '${user || ''}', false);`);
  await db.exec(`set role ${user ? 'authenticated' : 'anon'};`);
  try { return await db.query(sql, params); }
  finally { await db.exec('reset role;'); }
}

const shelfInsert = `insert into public.shelf_items (user_id, book_key, source, source_id, title, authors, status, client_updated_at)
  values ($1, $2, 'gutenberg', '1342', $3, '{Jane Austen}', 'reading', $4)
  on conflict (user_id, book_key) do update set title = excluded.title, status = excluded.status, client_updated_at = excluded.client_updated_at
  returning title`;

// User A writes a shelf item.
await as(A, shelfInsert, [A, 'gutenberg:1342', 'Pride and Prejudice', 1000]);
ok('user A can insert their own shelf item');

// User B cannot see it.
let r = await as(B, 'select * from public.shelf_items');
assert.equal(r.rows.length, 0);
ok("user B cannot see user A's shelf");

// User A can see it.
r = await as(A, 'select title from public.shelf_items');
assert.equal(r.rows.length, 1);
ok('user A sees exactly their own shelf');

// Anonymous visitors see nothing (no grant at all).
await assert.rejects(() => as(null, 'select * from public.shelf_items'));
ok('anonymous role is denied');

// User B cannot write a row claiming to be user A.
await assert.rejects(() => as(B, shelfInsert, [A, 'gutenberg:84', 'Frankenstein', 1000]));
ok("user B cannot insert into user A's shelf");

// User B cannot update or delete user A's row (silently affects 0 rows).
r = await as(B, `update public.shelf_items set title = 'hacked' where user_id = $1`, [A]);
assert.equal(r.affectedRows ?? 0, 0);
r = await as(B, `delete from public.shelf_items where user_id = $1`, [A]);
assert.equal(r.affectedRows ?? 0, 0);
r = await as(A, 'select title from public.shelf_items');
assert.equal(r.rows[0].title, 'Pride and Prejudice');
ok("user B cannot update or delete user A's rows");

// Last-writer-wins: an older write does not overwrite a newer one.
await as(A, shelfInsert, [A, 'gutenberg:1342', 'Older title', 500]);
r = await as(A, 'select title, client_updated_at from public.shelf_items');
assert.equal(r.rows[0].title, 'Pride and Prejudice');
ok('older write (client_updated_at 500 < 1000) is ignored');
await as(A, shelfInsert, [A, 'gutenberg:1342', 'Newer title', 2000]);
r = await as(A, 'select title from public.shelf_items');
assert.equal(r.rows[0].title, 'Newer title');
ok('newer write replaces the row');

// Annotations: B cannot take over A's annotation id through an upsert.
const annId = '33333333-3333-4333-8333-333333333333';
const annUpsert = `insert into public.annotations (id, user_id, book_key, kind, cfi, text_excerpt, color, client_updated_at)
  values ($1, $2, 'gutenberg:1342', 'highlight', 'epubcfi(/6/4!/4/2/1:0)', 'It is a truth', 'sun', $3)
  on conflict (id) do update set text_excerpt = excluded.text_excerpt, client_updated_at = excluded.client_updated_at`;
await as(A, annUpsert, [annId, A, 1000]);
await assert.rejects(() => as(B, annUpsert, [annId, B, 9999]));
r = await as(A, 'select user_id from public.annotations where id = $1', [annId]);
assert.equal(r.rows[0].user_id, A);
ok("user B cannot hijack user A's annotation id");

// user_id is pinned on update.
await assert.rejects(() => as(A, `update public.annotations set user_id = $1 where id = $2`, [B, annId]));
ok('a row cannot be moved to another account');

// Validation constraints.
await assert.rejects(() => as(A, `insert into public.annotations (id, user_id, book_key, kind, cfi, client_updated_at) values (gen_random_uuid(), $1, 'x:1', 'scribble', 'c', 1)`, [A]));
await assert.rejects(() => as(A, `insert into public.shelf_items (user_id, book_key, source, title, cover_url, client_updated_at) values ($1, 'import:1', 'import', 't', 'javascript:alert(1)', 1)`, [A]));
ok('check constraints reject invalid kinds and non-https cover URLs');

// 0002: Bible shelf items and quote annotations are accepted.
await as(A, `insert into public.shelf_items (user_id, book_key, source, title, client_updated_at) values ($1, 'bible', 'bible', 'Holy Bible', 1)`, [A]);
await as(A, `insert into public.annotations (id, user_id, book_key, kind, cfi, text_excerpt, client_updated_at) values (gen_random_uuid(), $1, 'bible', 'quote', 'John.3.16', 'For God so loved the world', 1)`, [A]);
ok('Bible shelf items and quote annotations are accepted (0002)');

// Server timestamps advance for the pull cursor.
r = await as(A, 'select updated_at from public.shelf_items');
assert.ok(r.rows[0].updated_at instanceof Date);
ok('server sets updated_at for sync cursors');

console.log(`\n${passed} database checks passed.`);
