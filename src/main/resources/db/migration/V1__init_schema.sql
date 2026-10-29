create table office (
    id uuid primary key,
    tenant_id varchar(128) not null,
    code varchar(64) not null,
    constraint uq_office_tenant_code unique (tenant_id, code)
);

create table employee (
    id uuid primary key,
    tenant_id varchar(128) not null,
    employee_id varchar(128) not null,
    name varchar(200),
    email varchar(320),
    phone varchar(32),
    manager varchar(200),
    office_code varchar(64),
    pickup_point varchar(500),
    special_assistance varchar(500),
    hr_version bigint not null,
    status varchar(32) not null,
    updated_at timestamptz not null,
    constraint uq_employee_tenant_employee unique (tenant_id, employee_id),
    constraint ck_employee_status check (status in ('ACTIVE', 'DEACTIVATED')),
    constraint fk_employee_office foreign key (tenant_id, office_code) references office (tenant_id, code)
);

create index idx_employee_tenant_active on employee (tenant_id) where status = 'ACTIVE';

create table outbox (
    id uuid primary key,
    tenant_id varchar(128) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(64) not null,
    payload jsonb not null,
    created_at timestamptz not null,
    published_at timestamptz
);

create index idx_outbox_unpublished on outbox (created_at) where published_at is null;

create table processed_event (
    id uuid primary key,
    tenant_id varchar(128) not null,
    employee_id varchar(128) not null,
    source_version bigint not null,
    processed_at timestamptz not null default now(),
    constraint uq_processed_event unique (tenant_id, employee_id, source_version)
);

create table import_run (
    id uuid primary key,
    tenant_id varchar(128) not null,
    status varchar(32) not null,
    started_at timestamptz not null,
    finished_at timestamptz,
    active_employee_count integer,
    export_row_count integer,
    missing_ratio numeric(8, 6),
    threshold numeric(8, 6),
    halt_reason varchar(255)
);
