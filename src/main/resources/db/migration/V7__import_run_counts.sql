alter table import_run
    add column unchanged_count integer,
    add column create_count integer,
    add column update_count integer,
    add column deactivate_count integer,
    add column missing_count integer;
