package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.Payment;
import com.deployhub.platform.models.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRazorpayOrderIdAndUserId(String razorpayOrderId, Long userId);

    /** Orders started recently and not (yet) known to be paid: checked with Razorpay in case verifying was missed. */
    List<Payment> findByUserIdAndStatusAndCreatedAtAfter(Long userId, PaymentStatus status, Instant after);

    /** Orders started recently, for the per-user limit. */
    long countByUserIdAndCreatedAtAfter(Long userId, Instant after);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.userId = :userId AND p.status = 'PAID' AND p.usedAt IS NULL")
    long countUnusedCredits(@Param("userId") Long userId);

    /** The oldest unused credit. Callers hold the user's row lock, so two deploys can't take the same one. */
    Optional<Payment> findFirstByUserIdAndStatusAndUsedAtIsNullOrderByIdAsc(Long userId, PaymentStatus status);

    /** CREATED -> PAID exactly once: 0 rows means a parallel request already recorded the payment. */
    @Modifying
    @Query("""
            UPDATE Payment p SET p.status = 'PAID', p.razorpayPaymentId = :paymentId, p.paidAt = :now
            WHERE p.id = :id AND p.status = 'CREATED'""")
    int markPaid(@Param("id") Long id, @Param("paymentId") String paymentId, @Param("now") Instant now);

    /** The deploy didn't happen (failed or cancelled): its credit can be used again. */
    @Modifying
    @Query("UPDATE Payment p SET p.usedAt = NULL, p.deploymentId = NULL WHERE p.deploymentId = :deploymentId")
    int releaseCredit(@Param("deploymentId") Long deploymentId);
}
