-- The 20 x 20 mirror box and 28 x 28 heart glass box contain twelve roses.
-- Correct their ERP/document, catalogue, website and SEO copy in every language.
-- Family membership is authoritative: include unpublished/inactive colour variants,
-- but keep URLs, dimensions, carton quantities, stock and prices unchanged.
-- The owner also approved HRT16-RD -> HRT12-RD (69) and FRAME16-RD -> FRAME12-RD
-- (81). IDs and foreign keys remain intact; only these two SKU values change.
-- A previous import said thirteen roses; correct that known count as well.
-- Existing issued documents and their historical line snapshots are not rewritten.
-- Each original row is retained in catalog_data_patch.before_state for recovery.
-- WebsiteRebuildService detects the changed public revision on application startup.
begin;
set local lock_timeout = '15s';
set local statement_timeout = '120s';

create table if not exists catalog_data_patch (
    patch_key varchar(120) primary key,
    applied_at timestamptz not null default now(),
    affected_rows integer not null,
    before_state jsonb not null
);

-- Only a number immediately describing roses is eligible, never a bare 16/13,
-- an identifier such as HRT16-RD, a price, or a measurement such as 16 cm.
create or replace function pg_temp.correct_glass_rose_count(value text)
returns text language plpgsql immutable strict as $function$
declare
    pair record;
    rose_suffix text := '(?=[[:space:]-]+(?:(?:preserved|gepreserveerde|prachtige|beautiful|konserviert(?:e|en)|stabilisiert(?:e|en)|solmayan|korunmuş|διατηρημέν(?:α|ων))[[:space:]]+)?(?:[Rr]oses?|[Rr]ozen|[Rr]osen|[Rr]osas|róż(?:ami|e)?|[Gg]ül(?:ler(?:i|in)?|lü)?|[Ττ]ριαντάφυλλ(?:α|ων))(?:[^[:alpha:]]|$))';
begin
    value := regexp_replace(value, '(^|[^[:alnum:]_])(?:16|13)' || rose_suffix, '\112', 'gi');
    for pair in select * from (values
        ('Zestien', 'Twaalf'), ('zestien', 'twaalf'),
        ('Sixteen', 'Twelve'), ('sixteen', 'twelve'),
        ('Seize', 'Douze'), ('seize', 'douze'),
        ('Sechzehn', 'Zwölf'), ('sechzehn', 'zwölf'),
        ('Dieciséis', 'Doce'), ('dieciséis', 'doce'),
        ('Szesnaście', 'Dwanaście'), ('szesnaście', 'dwanaście'),
        ('Dezasseis', 'Doze'), ('dezasseis', 'doze'),
        ('Dezesseis', 'Doze'), ('dezesseis', 'doze'),
        ('On altı', 'On iki'), ('on altı', 'on iki'),
        ('Δεκαέξι', 'Δώδεκα'), ('δεκαέξι', 'δώδεκα')
    ) as replacements(old_count, new_count) loop
        value := regexp_replace(value,
            '(^|[^[:alnum:]_])' || pair.old_count || rose_suffix,
            '\1' || pair.new_count, 'g');
    end loop;
    return value;
end
$function$;

do $migration$
declare
    family_ids bigint[];
    product_ids bigint[];
    content_ids bigint[];
    before_rows jsonb;
    changed integer := 0;
    row_count integer;
    target record;
    sku_change record;
    assignments text;
    differences text;
begin
    perform pg_advisory_xact_lock(hashtextextended('enrosed:glass-box-twelve-roses-2026-09-30', 0));
    if exists (select 1 from catalog_data_patch
               where patch_key = 'glass-box-twelve-roses-2026-09-30') then
        return;
    end if;

    lock table product_family, product_family_text, product, product_text,
               product_family_photo, content_translation, content_translation_text
        in share row exclusive mode;

    select coalesce(array_agg(id), '{}'::bigint[]) into family_ids
      from product_family
     where familykey in ('hearth-glass-flowerbox', 'glass-flowerbox');
    select coalesce(array_agg(id), '{}'::bigint[]) into product_ids
      from product
     where familyid = any(family_ids)
        or familykey in ('hearth-glass-flowerbox', 'glass-flowerbox');
    select coalesce(array_agg(id), '{}'::bigint[]) into content_ids
      from content_translation
     where scope = 'WEBSITE' and copy_key = 'home.flowerbox.item2.description';

    -- Validate both identities and destination uniqueness before changing any row.
    -- The earlier short-skus-2026-09-22 patch has its own one-time marker and
    -- runs first; it cannot restore the old SKU on later deployments.
    for sku_change in select * from (values
        (69::bigint, 'hearth-glass-flowerbox', 'HRT16-RD', 'HRT12-RD'),
        (81::bigint, 'glass-flowerbox', 'FRAME16-RD', 'FRAME12-RD')
    ) as sku_changes(product_id, family_key, old_sku, new_sku) loop
        if exists (select 1 from product where id = sku_change.product_id)
           and not exists (
               select 1 from product p join product_family f on f.id = p.familyid
                where p.id = sku_change.product_id and f.familykey = sku_change.family_key
                  and p.sku in (sku_change.old_sku, sku_change.new_sku)) then
            raise exception 'Twelve-rose glass box: product % identity changed; refusing a partial update', sku_change.product_id;
        end if;
        if exists (select 1 from product
                    where sku = sku_change.new_sku and id <> sku_change.product_id) then
            raise exception 'Twelve-rose glass box: destination SKU % is already in use', sku_change.new_sku;
        end if;
    end loop;

    select jsonb_build_object(
        'product_family', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                            from product_family t where id = any(family_ids)),
        'product_family_text', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                                 from product_family_text t where family_id = any(family_ids)),
        'product', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                     from product t where id = any(product_ids)),
        'product_text', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                          from product_text t where product_id = any(product_ids)),
        'product_family_photo', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                                  from product_family_photo t where family_id = any(family_ids)),
        'content_translation', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                                 from content_translation t where id = any(content_ids)),
        'content_translation_text', (select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)
                                      from content_translation_text t where content_translation_id = any(content_ids)))
      into before_rows;

    update product set sku = case id when 69 then 'HRT12-RD' when 81 then 'FRAME12-RD' end
     where (id = 69 and sku = 'HRT16-RD') or (id = 81 and sku = 'FRAME16-RD');
    get diagnostics row_count = row_count;
    changed := changed + row_count;

    -- This static list is the complete set of editable copy fields. Array/JSON
    -- fields contain strings, so the same count-only replacement preserves shape.
    for target in select * from (values
        ('product_family', 'id', family_ids,
            array['name', 'summary', 'description', 'format', 'highlightsjson', 'tagsjson', 'seotitle', 'seodescription']),
        ('product_family_text', 'family_id', family_ids,
            array['name', 'summary', 'description', 'format', 'highlightsjson', 'tagsjson', 'seotitle', 'seodescription']),
        ('product', 'id', product_ids, array['name', 'public_name', 'description']),
        ('product_text', 'product_id', product_ids, array['name', 'public_name', 'description']),
        ('product_family_photo', 'family_id', family_ids, array['alttextsource', 'alttextsjson']),
        ('content_translation_text', 'content_translation_id', content_ids, array['copy_value'])
    ) as targets(table_name, owner_column, owner_ids, copy_columns) loop
        select string_agg(format('%I = pg_temp.correct_glass_rose_count(%I)', c, c), ', '),
               string_agg(format('%I is distinct from pg_temp.correct_glass_rose_count(%I)', c, c), ' or ')
          into assignments, differences
          from unnest(target.copy_columns) c;
        execute format('update %I set %s where %I = any($1) and (%s)',
                       target.table_name, assignments, target.owner_column, differences)
            using target.owner_ids;
        get diagnostics row_count = row_count;
        changed := changed + row_count;
    end loop;

    -- Invalidate open family/website-copy editor snapshots as well as public hashes.
    update product_family set updatedat = now() where id = any(family_ids);
    update content_translation set revision = revision + 1, updatedat = now()
     where id = any(content_ids);

    insert into catalog_data_patch(patch_key, affected_rows, before_state)
    values ('glass-box-twelve-roses-2026-09-30', changed, before_rows);
    raise notice 'Corrected twelve-rose glass-box copy in % rows', changed;
end
$migration$;

commit;
