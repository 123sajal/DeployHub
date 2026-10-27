package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.BillingSummaryResponseDTO;
import com.deployhub.platform.models.DTO.PaymentOrderResponseDTO;
import com.deployhub.platform.models.DTO.PaymentVerifyRequestDTO;
import com.deployhub.platform.models.entities.Payment;
import com.deployhub.platform.models.entities.ProjectMaster;

import java.util.Optional;

public interface IBillingService {

    /** The current user's credits and the price; first checks Razorpay for recent orders paid but not verified. */
    BillingSummaryResponseDTO summary();

    /** Starts paying for one deploy: a Razorpay order the browser opens Checkout with. */
    PaymentOrderResponseDTO createOrder();

    /** Confirms a Checkout payment (signature and Razorpay's own record) and turns the order into a credit. */
    BillingSummaryResponseDTO verify(String orderId, PaymentVerifyRequestDTO request);

    /**
     * Inside the deploy's transaction, before the deployment is saved: free project, the user's first project
     * (which becomes the free one), or a paid credit. Throws PaymentRequiredException when none applies.
     *
     * @return the credit to mark used once the deployment has an id; empty for free deploys
     */
    Optional<Payment> admitDeploy(Long userId, ProjectMaster project);

    void useCredit(Payment credit, Long deploymentId);
}
