create table users (
    id uuid primary key,
    tenant_id varchar(128) not null,
    username varchar(128) not null,
    password_hash varchar(100) not null,
    role varchar(16) not null,
    employee_id varchar(128),
    constraint uq_users_username unique (username),
    constraint ck_users_role check (role in ('EMPLOYEE', 'ADMIN', 'OPERATOR')),
    constraint ck_users_employee check (
        (role = 'EMPLOYEE' and employee_id is not null)
        or (role <> 'EMPLOYEE' and employee_id is null)
    )
);
