alter table employee drop constraint ck_employee_status;

alter table employee
    add constraint ck_employee_status
        check (status in ('ACTIVE', 'DEACTIVATED', 'PLACEHOLDER'));
