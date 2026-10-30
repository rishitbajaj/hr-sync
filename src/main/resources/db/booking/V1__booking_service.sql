create table trip (
    tenant_id varchar(128) not null,
    trip_id varchar(128) not null,
    capacity integer not null check (capacity >= 1),
    primary key (tenant_id, trip_id)
);

create table booking (
    tenant_id varchar(128) not null,
    booking_id varchar(128) not null,
    employee_id varchar(128) not null,
    trip_id varchar(128) not null,
    starts_at timestamptz not null,
    status varchar(16) not null check (status in ('BOOKED', 'CANCELLED')),
    cancelled_by_event_id bigint,
    cancelled_at timestamptz,
    primary key (tenant_id, booking_id),
    constraint fk_booking_trip foreign key (tenant_id, trip_id) references trip (tenant_id, trip_id)
);

create index idx_booking_booked_employee_start
    on booking (tenant_id, employee_id, starts_at)
    where status = 'BOOKED';

create index idx_booking_cancelled_by_event
    on booking (tenant_id, cancelled_by_event_id)
    where cancelled_by_event_id is not null;
