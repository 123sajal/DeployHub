package com.deployhub.platform.exceptions;

import lombok.Getter;

/** The deploy needs a paid deploy credit and the user has none (402). */
@Getter
public class PaymentRequiredException extends RuntimeException {

    private final long price;
    private final String currency;

    public PaymentRequiredException(String message, long price, String currency) {
        super(message);
        this.price = price;
        this.currency = currency;
    }
}
