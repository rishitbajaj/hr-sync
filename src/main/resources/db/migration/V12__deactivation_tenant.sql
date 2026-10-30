alter table import_run_deactivation
    add column tenant_id varchar(128);

update import_run_deactivation as deactivation
set tenant_id = run.tenant_id
from import_run as run
where run.id = deactivation.run_id;

alter table import_run_deactivation
    alter column tenant_id set not null;

alter table import_run_deactivation
    drop constraint import_run_deactivation_pkey;

alter table import_run_deactivation
    add primary key (tenant_id, run_id, employee_id);
