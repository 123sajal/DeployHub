package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A Razorpay order that becomes one deploy credit once paid. Card and UPI details never reach DeployHub. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "razorpay_order_id", nullable = false, length = 40)
    private String razorpayOrderId;

    /** Set once the payment is verified. */
    @Column(name = "razorpay_payment_id", length = 40)
    private String razorpayPaymentId;

    /** In the currency's smallest unit (paise). */
    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Set when a deploy uses the credit; cleared again if that deploy fails or is cancelled. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** The deploy that used the credit (null once that deployment is deleted; usedAt still says it was used). */
    @Column(name = "deployment_id")
    private Long deploymentId;
}
