package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How footpaths become roads.
 *
 * <p>The colony's road network is emergent, which means its behaviour is
 * entirely decided by these thresholds: what counts as the same journey, how
 * much traffic earns paving, and which of thirty remembered routes is worth
 * building. Getting them wrong produces either no roads at all or a colony
 * that paves every stroll.</p>
 */
class RoutePolicyTest {

    // ------------------------------------------------------------------ identity

    @Test
    void twoTripsBetweenTheSamePlacesAreOneJourney() {
        int[] townCentre = {100, 64, 100};
        int[] mine = {300, 40, 100};
        // The second citizen set off from a few blocks away, as they all do.
        int[] nearlyTownCentre = {104, 64, 97};

        assertTrue(RoutePolicy.sameJourney(townCentre, mine, nearlyTownCentre, mine));
    }

    @Test
    void walkingBackCountsAsTheSameRoad() {
        int[] a = {0, 64, 0};
        int[] b = {200, 64, 0};
        // Remembering each direction separately would halve every use count,
        // and no road would ever be built.
        assertTrue(RoutePolicy.sameJourney(a, b, b, a));
    }

    @Test
    void differentDestinationsAreDifferentRoads() {
        int[] home = {0, 64, 0};
        assertFalse(RoutePolicy.sameJourney(home, new int[]{200, 64, 0},
                home, new int[]{0, 64, 200}));
    }

    // ------------------------------------------------------------------ shape

    @Test
    void aTrailIsThinnedToWaypoints() {
        List<int[]> trail = new ArrayList<>();
        for (int x = 0; x <= 100; x++) trail.add(new int[]{x, 64, 0});

        List<int[]> waypoints = RoutePolicy.compact(trail);
        assertTrue(waypoints.size() < trail.size());
        assertTrue(waypoints.size() <= RoutePolicy.MAX_WAYPOINTS);
        // The ends are the two things a route cannot afford to forget.
        assertEquals(0, waypoints.get(0)[0]);
        assertEquals(100, waypoints.get(waypoints.size() - 1)[0]);
    }

    @Test
    void aVeryLongTrailIsThinnedEvenlyNotTruncated() {
        List<int[]> trail = new ArrayList<>();
        for (int x = 0; x <= 2000; x += 2) trail.add(new int[]{x, 64, 0});

        List<int[]> waypoints = RoutePolicy.compact(trail);
        assertTrue(waypoints.size() <= RoutePolicy.MAX_WAYPOINTS);
        // Truncating would leave the far half of the road unrepresented, and
        // that half needs paving just as much as the near half.
        assertEquals(2000, waypoints.get(waypoints.size() - 1)[0]);
    }

    @Test
    void pacingOnTheSpotProducesOneWaypoint() {
        List<int[]> trail = new ArrayList<>();
        for (int i = 0; i < 20; i++) trail.add(new int[]{10, 64, 10});
        assertEquals(1, RoutePolicy.compact(trail).size());
    }

    @Test
    void routeLengthFollowsThePolyline() {
        List<int[]> line = List.of(new int[]{0, 64, 0}, new int[]{30, 64, 0},
                new int[]{30, 64, 40});
        assertEquals(70, RoutePolicy.lengthOf(line));
    }

    @Test
    void aShortStrollIsNotARoad() {
        assertFalse(RoutePolicy.worthRemembering(10));
        assertTrue(RoutePolicy.worthRemembering(RoutePolicy.MIN_ROUTE_LENGTH));
    }

    // ------------------------------------------------------------------ upkeep

    @Test
    void trafficEarnsEachGradeInTurn() {
        assertEquals(RoadGrade.TRACK, RoadGrade.earnedBy(0));
        assertEquals(RoadGrade.CLEARED, RoadGrade.earnedBy(RoadGrade.CLEARED.usesRequired));
        assertEquals(RoadGrade.PAVED, RoadGrade.earnedBy(RoadGrade.PAVED.usesRequired));
        assertEquals(RoadGrade.SIGNPOSTED, RoadGrade.earnedBy(1000));
    }

    @Test
    void aRoadIsUpgradedOneGradeAtATime() {
        // Plenty of traffic, but it still gets cleared before it gets paved:
        // jumping straight to signposting an unwalkable route is absurd.
        assertEquals(RoadGrade.CLEARED, RoutePolicy.upgradeFor(RoadGrade.TRACK, 1000, 0));
        assertEquals(RoadGrade.PAVED, RoutePolicy.upgradeFor(RoadGrade.CLEARED, 1000, 0));
    }

    @Test
    void aRoadThatHasNotEarnedItsNextGradeIsLeftAlone() {
        assertNull(RoutePolicy.upgradeFor(RoadGrade.TRACK, 1, 0));
        assertNull(RoutePolicy.upgradeFor(RoadGrade.SIGNPOSTED, 10_000, 0));
    }

    @Test
    void aDeadlyRouteIsNeverImproved() {
        // Paving the path through the ravine that keeps killing people is the
        // wrong answer; the colony should be routing around it.
        assertNull(RoutePolicy.upgradeFor(RoadGrade.TRACK, 1000, RoutePolicy.DANGEROUS_AT));
    }

    @Test
    void busyAndRecentBeatsQuietAndStale() {
        double busy = RoutePolicy.upkeepScore(50, 0, 200, 0);
        double stale = RoutePolicy.upkeepScore(50, 200_000, 200, 0);
        assertTrue(busy > stale);

        double safe = RoutePolicy.upkeepScore(50, 0, 200, 0);
        double risky = RoutePolicy.upkeepScore(50, 0, 200, 3);
        assertTrue(safe > risky);
    }

    @Test
    void anUnusedRouteIsWorthNothing() {
        assertEquals(0, RoutePolicy.upkeepScore(0, 0, 500, 0));
    }

    @Test
    void aBuiltRoadIsWorthTravellingEvenWhenItIsNotTheShortestLine() {
        double paved = RoutePolicy.travelValue(RoadGrade.PAVED, 60, 0);
        double track = RoutePolicy.travelValue(RoadGrade.TRACK, 60, 0);
        assertTrue(paved > track);
    }

    @Test
    void aDeadlyRouteIsWorthNothingToTravel() {
        assertEquals(0, RoutePolicy.travelValue(RoadGrade.SIGNPOSTED, 500,
                RoutePolicy.DANGEROUS_AT));
    }
}
