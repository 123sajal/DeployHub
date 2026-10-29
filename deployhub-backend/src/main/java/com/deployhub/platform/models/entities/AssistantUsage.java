package com.deployhub.platform.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** AI use of one subject ("user:&lt;id&gt;" or "ip:&lt;address&gt;") on one day (India time, yyyy-MM-dd). */
@Entity
@Table(name = "assistant_usage")
@Getter
@Setter
@NoArgsConstructor
public class AssistantUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_day", nullable = false, length = 10)
    private String usageDay;

    @Column(nullable = false, length = 60)
    private String subject;

    @Column(nullable = false)
    private long requests;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** Thousandths of a paisa (100 000 = ₹1). */
    @Column(name = "cost_millipaise", nullable = false)
    private long costMillipaise;
}
