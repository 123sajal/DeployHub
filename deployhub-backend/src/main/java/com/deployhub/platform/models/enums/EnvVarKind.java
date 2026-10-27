package com.deployhub.platform.models.enums;

/** What kind of value an environment variable the app needs is, which decides how DeployHub can help. */
public enum EnvVarKind {
    /** The app's own secret (JWT_SECRET, SESSION_SECRET, ...): DeployHub generates a strong random value. */
    SECRET,
    /** Set by DeployHub's one-click database (DB_HOST, DATABASE_URL, ...). */
    DATABASE,
    /** A database setting under another name (DB_USERNAME, ...): copied from the database's variable. */
    DATABASE_ALIAS,
    /** A normal setting with a usable example value (JWT_EXPIRES_IN=24h, NODE_ENV=production). */
    SETTING,
    /** Set by DeployHub itself (PORT): nothing to do. */
    PLATFORM,
    /** A key from another service (Stripe, SMTP, AWS, ...): only the user can get it. */
    EXTERNAL,
    /** Its example points at the developer's computer (localhost): needs a real address, or can be left out. */
    LOCAL_ONLY,
    /** Needed, but nothing tells what it should be. */
    UNKNOWN
}
