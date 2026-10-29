-- Private repositories: a read-only access token per project, encrypted like environment variables
-- (AES-256-GCM with DEPLOYHUB_SECRETS_KEY). Never returned by the API. Additive only.
ALTER TABLE projects ADD (repo_token_encrypted VARCHAR2(2000), repo_token_updated_at TIMESTAMP(6) WITH TIME ZONE);
