create table import_run_deactivation (
    run_id uuid not null references import_run (id),
    employee_id varchar(128) not null,
    hr_version bigint not null,
    primary key (run_id, employee_id)
);
