package com.deployhub.platform.models.enums;

/** What the assistant is asked to explain. */
public enum AssistantTopic {
    /** A deployment that failed (build error, crash on start, health check). Uses the explain model. */
    DEPLOYMENT_FAILED,
    /** The pre-deploy check found settings the app needs. */
    MISSING_SETTINGS,
    /** Razorpay reported a failed payment. */
    PAYMENT_FAILED,
    /** A database import was refused. */
    IMPORT_REFUSED
}
