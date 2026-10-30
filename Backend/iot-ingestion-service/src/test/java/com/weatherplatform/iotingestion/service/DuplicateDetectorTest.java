package com.weatherplatform.iotingestion.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateDetectorTest {

    private DuplicateDetector duplicateDetector;

    @BeforeEach
    void setUp() {
        duplicateDetector = new DuplicateDetector(300, 1000);
    }

    @Test
    void firstSightIsNotDuplicateAndRepeatsAre() {
        String msgId = "test-msg-123";

        assertFalse(duplicateDetector.isDuplicate(msgId), "First delivery is new");
        assertTrue(duplicateDetector.isDuplicate(msgId), "Second delivery is a duplicate");
        assertTrue(duplicateDetector.isDuplicate(msgId), "And it stays a duplicate");
    }

    @Test
    void distinctIdsAreIndependent() {
        assertFalse(duplicateDetector.isDuplicate("msg-a"));
        assertFalse(duplicateDetector.isDuplicate("msg-b"));
        assertTrue(duplicateDetector.isDuplicate("msg-a"));
    }

    @Test
    void nullOrBlankIdIsNeverTreatedAsDuplicate() {
        assertFalse(duplicateDetector.isDuplicate(null));
        assertFalse(duplicateDetector.isDuplicate(""));
        assertFalse(duplicateDetector.isDuplicate("   "));
    }

    @Test
    @DisplayName("The cache sheds rather than growing without bound")
    void cacheIsBoundedAndShedsWhenFull() {
        DuplicateDetector small = new DuplicateDetector(300, 10);

        for (int i = 0; i < 10; i++) {
            assertFalse(small.isDuplicate("msg-" + i));
        }
        assertEquals(10, small.trackedCount());

        // Crossing the bound clears the cache instead of exhausting the heap.
        // Correctness is unaffected: the database constraint is authoritative.
        small.isDuplicate("msg-overflow");
        assertTrue(small.trackedCount() <= 10);
    }

    @Test
    @DisplayName("forget releases a claim so a redelivery is processed, not discarded")
    void forgetLetsARedeliveryThrough() {
        String msgId = "reading-in-flight-when-the-broker-died";

        // The claim is taken as the check is made.
        assertFalse(duplicateDetector.isDuplicate(msgId));
        assertTrue(duplicateDetector.isDuplicate(msgId));

        // The publish failed, so the claim is released and SQS may redeliver.
        duplicateDetector.forget(msgId);

        assertFalse(duplicateDetector.isDuplicate(msgId),
                "A message whose publish failed must be processable again; "
                        + "treating the redelivery as a duplicate deletes a reading "
                        + "that never reached the bus");
    }

    @Test
    @DisplayName("forget only releases the id it is given")
    void forgetIsNarrow() {
        assertFalse(duplicateDetector.isDuplicate("msg-a"));
        assertFalse(duplicateDetector.isDuplicate("msg-b"));

        duplicateDetector.forget("msg-a");

        assertFalse(duplicateDetector.isDuplicate("msg-a"), "released");
        assertTrue(duplicateDetector.isDuplicate("msg-b"), "untouched");
    }

    @Test
    @DisplayName("forget tolerates a null or blank id and an id never seen")
    void forgetIsSafeOnNothing() {
        duplicateDetector.forget(null);
        duplicateDetector.forget("");
        duplicateDetector.forget("   ");
        duplicateDetector.forget("never-seen");
        assertEquals(0, duplicateDetector.trackedCount());
    }
}
