package dev.instigatehardcore.participation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AttemptAdmissionPolicyTest {

    @Test
    void allowsNewPlayerWhenLateJoiningEnabled() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                true
            );

        assertTrue(
            policy.mayEnter(
                false,
                5
            )
        );
    }

    @Test
    void allowsNewPlayerWhenLateJoiningEnabledEvenWithLargeAttempt() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                true
            );

        assertTrue(
            policy.mayEnter(
                false,
                25
            )
        );
    }

    @Test
    void blocksNewPlayerWhenLateJoiningDisabledAndAttemptAlreadyStarted() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                false
            );

        assertFalse(
            policy.mayEnter(
                false,
                5
            )
        );
    }

    @Test
    void existingParticipantCanAlwaysRejoinWhenLateJoiningDisabled() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                false
            );

        assertTrue(
            policy.mayEnter(
                true,
                5
            )
        );
    }

    @Test
    void existingParticipantCanAlwaysRejoinWhenLateJoiningEnabled() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                true
            );

        assertTrue(
            policy.mayEnter(
                true,
                5
            )
        );
    }

    @Test
    void firstPlayerCanBootstrapEmptyAttemptWhenLateJoiningDisabled() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                false
            );

        assertTrue(
            policy.mayEnter(
                false,
                0
            )
        );
    }

    @Test
    void secondNewPlayerCannotJoinWhenLateJoiningDisabled() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                false
            );

        assertFalse(
            policy.mayEnter(
                false,
                1
            )
        );
    }

    @Test
    void rejectsNegativeParticipantCount() {
        AttemptAdmissionPolicy policy =
            new AttemptAdmissionPolicy(
                true
            );

        assertThrows(
            IllegalArgumentException.class,
            () ->
                policy.mayEnter(
                    false,
                    -1
                )
        );
    }

    @Test
    void exposesConfiguredLateJoinState() {
        AttemptAdmissionPolicy enabled =
            new AttemptAdmissionPolicy(
                true
            );

        AttemptAdmissionPolicy disabled =
            new AttemptAdmissionPolicy(
                false
            );

        assertTrue(
            enabled.allowsLateJoiners()
        );

        assertFalse(
            disabled.allowsLateJoiners()
        );
    }
}