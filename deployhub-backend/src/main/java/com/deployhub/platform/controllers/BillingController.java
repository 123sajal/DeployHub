package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.BillingSummaryResponseDTO;
import com.deployhub.platform.models.DTO.PaymentOrderResponseDTO;
import com.deployhub.platform.models.DTO.PaymentVerifyRequestDTO;
import com.deployhub.platform.services.Interface.IBillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Paid deploys: the user's credits, buying one through Razorpay Checkout, and confirming the payment. */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final IBillingService billingService;

    @GetMapping
    public BillingSummaryResponseDTO summary() {
        return billingService.summary();
    }

    /** 201: a Razorpay order for one deploy; 503 when payments aren't set up. */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderResponseDTO createOrder() {
        return billingService.createOrder();
    }

    /** After Checkout succeeds: 200 with the new credit count, 400 if the payment can't be confirmed. */
    @PostMapping("/orders/{orderId}/verify")
    public BillingSummaryResponseDTO verify(@PathVariable String orderId,
                                            @Valid @RequestBody PaymentVerifyRequestDTO request) {
        return billingService.verify(orderId, request);
    }
}
