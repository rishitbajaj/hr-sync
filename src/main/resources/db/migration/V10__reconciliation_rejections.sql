alter table import_run
    add column rejected_count integer;

create table import_run_rejection (
    run_id uuid not null references import_run (id),
    employee_id varchar(128) not null,
    reason varchar(64) not null,
    primary key (run_id, employee_id)
);
