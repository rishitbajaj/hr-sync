create unique index uq_import_run_tenant_running
    on import_run (tenant_id)
    where status = 'RUNNING';
