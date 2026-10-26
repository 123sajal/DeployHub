package com.deployhub.platform.deployment;

import com.deployhub.platform.exceptions.InvalidStateTransitionException;
import com.deployhub.platform.models.enums.DeploymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static com.deployhub.platform.models.enums.DeploymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentStateMachineTest {

    @Test
    void happyPathIsAllowed() {
        assertThat(DeploymentStateMachine.canTransition(QUEUED, BUILDING)).isTrue();
        assertThat(DeploymentStateMachine.canTransition(BUILDING, STARTING)).isTrue();
        assertThat(DeploymentStateMachine.canTransition(STARTING, LIVE)).isTrue();
        assertThat(DeploymentStateMachine.canTransition(LIVE, SUPERSEDED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStatus.class, names = {"QUEUED", "BUILDING", "STARTING"})
    void inProgressStatesCanFailOrBeCancelled(DeploymentStatus from) {
        assertThat(DeploymentStateMachine.canTransition(from, FAILED)).isTrue();
        assertThat(DeploymentStateMachine.canTransition(from, CANCELLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStatus.class, names = {"FAILED", "CANCELLED", "SUPERSEDED"})
    void terminalStatesHaveNoWayOut(DeploymentStatus terminal) {
        for (DeploymentStatus target : DeploymentStatus.values()) {
            assertThat(DeploymentStateMachine.canTransition(terminal, target))
                    .as("%s -> %s", terminal, target).isFalse();
        }
    }

    @Test
    void cannotSkipStatesOrGoBackwards() {
        Set<DeploymentStatus> fromQueued = EnumSet.noneOf(DeploymentStatus.class);
        for (DeploymentStatus target : DeploymentStatus.values()) {
            if (DeploymentStateMachine.canTransition(QUEUED, target)) {
                fromQueued.add(target);
            }
        }
        assertThat(fromQueued).containsExactlyInAnyOrder(BUILDING, FAILED, CANCELLED);
        assertThat(DeploymentStateMachine.canTransition(STARTING, BUILDING)).isFalse();
        assertThat(DeploymentStateMachine.canTransition(LIVE, CANCELLED)).isFalse();
        assertThat(DeploymentStateMachine.canTransition(LIVE, FAILED)).isFalse();
    }

    @Test
    void assertTransitionThrowsForIllegalMove() {
        assertThatThrownBy(() -> DeploymentStateMachine.assertTransition(FAILED, BUILDING))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FAILED").hasMessageContaining("BUILDING");
    }
}
