package com.deployhub.platform.assistant;

import java.util.List;
import java.util.Optional;

/** Spots likely typos in common email domains ("gmial.com" -> "gmail.com"). Suggestions only; nothing is changed. */
public final class EmailTypos {

    private static final List<String> COMMON = List.of("gmail.com", "yahoo.com", "yahoo.in", "yahoo.co.in",
            "outlook.com", "hotmail.com", "live.com", "icloud.com", "rediffmail.com", "protonmail.com", "proton.me");

    /** Real domains that are close to a common one but must never be "corrected" (mail.com is not gmail.com). */
    private static final List<String> KNOWN = List.of("mail.com", "gmx.com", "aol.com", "me.com", "zoho.com",
            "ymail.com", "email.com", "inbox.com");

    private EmailTypos() {
    }

    /** A corrected address when the domain is one or two letters away from a common one, e.g. a@gmial.com. */
    public static Optional<String> suggest(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return Optional.empty();
        }
        String domain = email.substring(at + 1).toLowerCase();
        if (COMMON.contains(domain) || KNOWN.contains(domain)) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : COMMON) {
            int distance = distance(domain, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        // Two edits catch "gmial.com" and "gmail.con"; more would start "fixing" real domains.
        int allowed = domain.length() <= 7 ? 1 : 2;
        return bestDistance <= allowed ? Optional.of(email.substring(0, at + 1) + best) : Optional.empty();
    }

    /** Edit distance where swapping two neighbouring letters counts as one edit. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
