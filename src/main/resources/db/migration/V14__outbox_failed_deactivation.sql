alter table outbox
    add column failure_reason varchar(64);

alter table outbox drop constraint ck_outbox_status;

alter table outbox
    add constraint ck_outbox_status check (status in ('PENDING', 'PUBLISHED', 'FAILED', 'DISCARDED'));

create table outbox_discard (
    event_id bigint primary key,
    reason varchar(500) not null,
    discarded_at timestamptz not null
);

create index idx_outbox_failed_deactivation
    on outbox (tenant_id, aggregate_id, id)
    where status = 'FAILED' and event_type = 'EMPLOYEE_DEACTIVATED';
