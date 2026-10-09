-- Mavis Library 0.2: the built-in Bible on the shelf, and saved quotes
-- (favorite passages) in every book.

alter table public.shelf_items drop constraint if exists shelf_items_source_check;
alter table public.shelf_items add constraint shelf_items_source_check
  check (source in ('gutenberg', 'openlibrary', 'import', 'bible'));

alter table public.annotations drop constraint if exists annotations_kind_check;
alter table public.annotations add constraint annotations_kind_check
  check (kind in ('bookmark', 'highlight', 'note', 'quote'));
