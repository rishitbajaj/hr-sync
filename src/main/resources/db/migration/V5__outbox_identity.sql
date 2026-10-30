alter table outbox drop column id;

alter table outbox
    add column id bigint generated always as identity primary key;

create index idx_outbox_unpublished_id on outbox (id) where published_at is null;
