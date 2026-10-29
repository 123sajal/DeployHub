package com.deployhub.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Period;
import java.util.List;

/**
 * Student check (the discount itself is deployhub.billing.student-discount).
 *
 * @param collegeEmailSuffixes  email endings accepted as college addresses, e.g. ".ac.in"
 * @param defaultValidity       how long a check lasts when the card shows no date, or for a college email
 * @param maxValidity           a card's date is used up to this far ahead (guards against misread years)
 * @param maxIdCardBytes        largest student ID photo accepted
 * @param maxIdChecksPerDay     ID photos one user may have checked per day (each one is an AI call)
 */
@ConfigurationProperties(prefix = "deployhub.students")
public record StudentProperties(List<String> collegeEmailSuffixes, Period defaultValidity, Period maxValidity,
                                long maxIdCardBytes, int maxIdChecksPerDay) {
}
