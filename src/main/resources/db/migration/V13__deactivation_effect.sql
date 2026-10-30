create table deactivation_effect (
    event_id bigint primary key,
    tenant_id varchar(128) not null,
    employee_id varchar(128) not null,
    cancelled_booking_ids jsonb not null,
    seats_freed integer not null,
    processed_at timestamptz not null
);
