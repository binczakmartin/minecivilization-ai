package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomewardPolicyTest {
    @Test void failedWalkIsNotReissuedImmediately() {
        HomewardPolicy policy = new HomewardPolicy();

        assertEquals(HomewardPolicy.Action.START, policy.evaluate(121, 1_000));
        policy.failed(1_000);

        assertEquals(HomewardPolicy.Action.WAIT, policy.evaluate(121, 1_000));
        assertEquals(HomewardPolicy.Action.WAIT, policy.evaluate(121, 1_199));
        assertEquals(HomewardPolicy.Action.START, policy.evaluate(121, 1_200));
    }

    @Test void repeatedFailuresBackOffAndAreCapped() {
        assertEquals(200, HomewardPolicy.backoffTicks(1));
        assertEquals(400, HomewardPolicy.backoffTicks(2));
        assertEquals(800, HomewardPolicy.backoffTicks(3));
        assertEquals(1_600, HomewardPolicy.backoffTicks(4));
        assertEquals(3_200, HomewardPolicy.backoffTicks(5));
        assertEquals(3_200, HomewardPolicy.backoffTicks(20));
    }

    @Test void reachingTheBroadColonyLeashStopsTheReturnTrip() {
        HomewardPolicy policy = new HomewardPolicy();
        policy.failed(1_000);

        assertEquals(HomewardPolicy.Action.WITHIN_LEASH, policy.evaluate(119, 1_001));
        assertEquals(0, policy.failureCount());
        assertEquals(HomewardPolicy.Action.START, policy.evaluate(121, 1_002));
    }

    @Test void reachingHomeResetsRecovery() {
        HomewardPolicy policy = new HomewardPolicy();
        policy.failed(1_000);

        assertEquals(HomewardPolicy.Action.AT_HOME, policy.evaluate(59, 1_500));
        assertEquals(0, policy.failureCount());
        assertEquals(HomewardPolicy.Action.START, policy.evaluate(121, 1_501));
    }
}
