alter table outbox
    add column status varchar(16) not null default 'PENDING',
    add column attempts integer not null default 0,
    add column next_attempt_at timestamptz;

update outbox
set status = 'PUBLISHED'
where published_at is not null;

alter table outbox
    add constraint ck_outbox_status check (status in ('PENDING', 'PUBLISHED', 'FAILED'));

create index idx_outbox_pending on outbox (id) where status = 'PENDING';
