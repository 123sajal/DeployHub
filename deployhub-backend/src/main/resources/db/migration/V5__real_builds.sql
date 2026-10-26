-- Milestone 6: real builds in Docker. Additive only (start_command becomes optional).

-- The port the app listens on inside its container, and the path the health check requests.
-- NULL = the default for the app type (e.g. 3000 for Node.js) / "/".
ALTER TABLE projects ADD (
    app_port           NUMBER(5),
    health_check_path  VARCHAR2(200)
);
-- Dockerfile projects may leave the start command empty: the image's own CMD is used.
ALTER TABLE projects MODIFY (start_command NULL);

ALTER TABLE deployments ADD (
    resolved_commit  VARCHAR2(40),   -- exact commit SHA that was built (rollbacks redeploy exactly this)
    image_ref        VARCHAR2(200),  -- Docker image the build produced
    container_name   VARCHAR2(100),  -- container running the app (while LIVE)
    host_port        NUMBER(5)       -- port on this machine (127.0.0.1) the app is reachable on
);
