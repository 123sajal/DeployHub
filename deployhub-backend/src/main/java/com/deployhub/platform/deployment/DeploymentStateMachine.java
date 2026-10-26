package com.deployhub.platform.deployment;

import com.deployhub.platform.exceptions.InvalidStateTransitionException;
import com.deployhub.platform.models.enums.DeploymentStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.deployhub.platform.models.enums.DeploymentStatus.*;

/**
 * The single source of truth for which status changes are legal.
 *
 * <pre>
 * QUEUED -> BUILDING -> STARTING -> LIVE -> SUPERSEDED
 *   |          |           |
 *   +----------+-----------+--> FAILED | CANCELLED   (terminal)
 * </pre>
 */
public final class DeploymentStateMachine {

    private static final Map<DeploymentStatus, Set<DeploymentStatus>> ALLOWED = new EnumMap<>(DeploymentStatus.class);

    static {
        ALLOWED.put(QUEUED, EnumSet.of(BUILDING, FAILED, CANCELLED));
        ALLOWED.put(BUILDING, EnumSet.of(STARTING, FAILED, CANCELLED));
        ALLOWED.put(STARTING, EnumSet.of(LIVE, FAILED, CANCELLED));
        ALLOWED.put(LIVE, EnumSet.of(SUPERSEDED));
        ALLOWED.put(SUPERSEDED, EnumSet.noneOf(DeploymentStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(DeploymentStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(DeploymentStatus.class));
    }

    private DeploymentStateMachine() {
    }

    public static boolean canTransition(DeploymentStatus from, DeploymentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void assertTransition(DeploymentStatus from, DeploymentStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }
}
