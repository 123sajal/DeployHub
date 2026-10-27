package com.deployhub.platform.exceptions;

/** A payment Checkout reported can't be confirmed: wrong signature, wrong order or amount, or not completed (400). */
public class PaymentVerificationException extends RuntimeException {

    public PaymentVerificationException(String message) {
        super(message);
    }
}
