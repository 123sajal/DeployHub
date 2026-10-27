package com.deployhub.platform.models.enums;

public enum PaymentStatus {
    /** A Razorpay order exists; nothing has been paid (yet). */
    CREATED,
    /** Paid and verified: one deploy credit. */
    PAID
}
