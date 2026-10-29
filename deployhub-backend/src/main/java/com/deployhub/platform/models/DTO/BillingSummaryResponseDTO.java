package com.deployhub.platform.models.DTO;

/**
 * @param enabled              false = every deploy is free
 * @param paymentsConfigured   whether this DeployHub can take payments (Razorpay keys set)
 * @param pricePerDeploy       in the currency's smallest unit (paise)
 * @param credits              paid deploys bought and not used yet
 * @param freeProjectAvailable true while freeProjectsLeft > 0: the next new project deployed will be free
 * @param freeProjects         free projects per user (the setting)
 * @param freeProjectsLeft     free projects this user can still get
 * @param yourPrice            what one deploy costs this user (pricePerDeploy minus the student discount, if any)
 * @param verifiedStudent      the student discount applies today
 * @param studentDiscount      the discount verified students get (paise)
 */
public record BillingSummaryResponseDTO(boolean enabled, boolean paymentsConfigured, long pricePerDeploy,
                                        String currency, long credits, boolean freeProjectAvailable,
                                        int freeProjects, int freeProjectsLeft, long yourPrice,
                                        boolean verifiedStudent, long studentDiscount) {
}
