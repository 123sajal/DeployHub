package com.deployhub.platform.models.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** What Razorpay Checkout hands the browser after a successful payment. */
public record PaymentVerifyRequestDTO(
        @NotBlank
        @Pattern(regexp = "^pay_[A-Za-z0-9]{1,30}$", message = "is not a Razorpay payment id")
        String paymentId,

        /** HMAC-SHA256 of "orderId|paymentId" with the key secret, hex. */
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$", message = "is not a Razorpay signature")
        String signature) {
}
