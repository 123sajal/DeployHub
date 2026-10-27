package com.deployhub.platform.services.Interface;

import java.util.List;

/**
 * The payment provider's server API (Razorpay). Only DeployHub's backend talks to it, with the secret key;
 * the browser only ever gets the public key id and an order id.
 */
public interface IPaymentGateway {

    /** Creates an order for amount (smallest unit, e.g. paise) and returns its id ("order_..."). */
    String createOrder(long amount, String currency, String receipt);

    GatewayPayment fetchPayment(String paymentId);

    /** Every payment attempt made for the order (failed ones included). */
    List<GatewayPayment> fetchOrderPayments(String orderId);

    /** Takes the money of an authorized payment (accounts without automatic capture leave payments authorized). */
    GatewayPayment capture(String paymentId, long amount, String currency);

    /** @param status Razorpay's: created, authorized, captured, refunded or failed */
    record GatewayPayment(String id, String orderId, String status, long amount, String currency) {

        public boolean authorized() {
            return "authorized".equals(status);
        }

        public boolean captured() {
            return "captured".equals(status);
        }
    }
}
