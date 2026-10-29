-- BUG-17: deleting several projects (or deployments) at the same moment could deadlock in Oracle (ORA-00060).
-- idempotency_keys.deployment_id references deployments ON DELETE CASCADE but had no index, so every deployment delete
-- had to lock the whole idempotency_keys table; two deletes taking those locks in different orders deadlocked.
-- It was the only foreign key without an index. Additive only.
CREATE INDEX ix_idem_deployment ON idempotency_keys (deployment_id);
