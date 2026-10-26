-- Public links: an opt-in https link per project (Cloudflare quick tunnel to the project's address). Additive only.
-- public_link_state: NULL/'OFF' = private (default), 'ON' = a public link is wanted.
-- public_link_url: the link as last seen; quick-tunnel links change when the tunnel restarts.
ALTER TABLE projects ADD (
    public_link_state  VARCHAR2(10),
    public_link_url    VARCHAR2(200)
);
