create index idx_outbox_pending_created
    on outbox (created_at)
    where status = 'PENDING';

create index idx_outbox_failed_id
    on outbox (id)
    where status = 'FAILED';
