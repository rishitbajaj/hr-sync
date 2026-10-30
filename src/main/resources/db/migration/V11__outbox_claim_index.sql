update outbox
set next_attempt_at = created_at
where next_attempt_at is null
  and status = 'PENDING';

drop index if exists idx_outbox_unpublished;
drop index if exists idx_outbox_unpublished_id;
drop index if exists idx_outbox_pending;

create index idx_outbox_pending_id
    on outbox (id)
    include (tenant_id, aggregate_id, next_attempt_at)
    where status = 'PENDING';

create index idx_outbox_pending_aggregate
    on outbox (tenant_id, aggregate_id, id)
    where status = 'PENDING';
