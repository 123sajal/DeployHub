package com.deployhub.platform.models.DTO;

/**
 * What the browser needs to open Razorpay Checkout. keyId is the public key id; the secret never leaves the server.
 *
 * @param amount in the currency's smallest unit (paise)
 */
public record PaymentOrderResponseDTO(String orderId, String keyId, long amount, String currency, String name,
                                      String email) {
}
