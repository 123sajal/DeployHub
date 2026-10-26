-- Milestone 5: rollback. Additive only.

-- A rollback is a new deployment that redeploys an earlier one; this points at that earlier deployment.
-- ON DELETE SET NULL so deleting a project (all its deployments at once) never trips over the self-reference.
ALTER TABLE deployments ADD (rollback_of_id NUMBER(19));
ALTER TABLE deployments ADD CONSTRAINT fk_deployments_rollback_of
    FOREIGN KEY (rollback_of_id) REFERENCES deployments (id) ON DELETE SET NULL;
-- Oracle locks the whole child table when a parent row is deleted unless the foreign key is indexed.
CREATE INDEX ix_deployments_rollback_of ON deployments (rollback_of_id);

-- Dashboard metrics read "deployments that started / finished in the last hour".
CREATE INDEX ix_deployments_started ON deployments (started_at);
CREATE INDEX ix_deployments_finished ON deployments (finished_at);
