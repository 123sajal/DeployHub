-- Free projects per user become a setting (deployhub.billing.free-projects, 2 by default) instead of exactly one,
-- so a user can count how many they have used. Additive only.
ALTER TABLE users ADD (free_projects_used NUMBER(3) DEFAULT 0 NOT NULL);

-- Free projects claimed by a deploy count. V10's marking of users who already had projects before billing
-- (free_project_claimed_at = created_at) doesn't: those projects were free anyway, so those users get the full number.
UPDATE users SET free_projects_used = 1
WHERE free_project_claimed_at IS NOT NULL AND free_project_claimed_at <> created_at;
