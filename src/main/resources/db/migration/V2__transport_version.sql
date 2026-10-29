alter table employee
    add column transport_version bigint not null default 0;

alter table processed_event
    add column channel varchar(16) not null default 'HR';

alter table processed_event
    drop constraint uq_processed_event;

alter table processed_event
    add constraint uq_processed_event unique (tenant_id, employee_id, channel, source_version);
