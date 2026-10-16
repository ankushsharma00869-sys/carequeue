package com.carequeue.service;

import com.carequeue.entity.EmergencySeverity;
import com.carequeue.entity.QueuePriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit test of the severity -> priorityRank mapping (no Spring context needed). */
class EmergencyRankTest {

    @Test
    @DisplayName("CRITICAL < HIGH < MODERATE numerically, so ORDER BY priorityRank ASC serves CRITICAL first")
    void severityOrdering() {
        int critical = QueueService.resolveEmergencyPriorityRank(EmergencySeverity.CRITICAL);
        int high = QueueService.resolveEmergencyPriorityRank(EmergencySeverity.HIGH);
        int moderate = QueueService.resolveEmergencyPriorityRank(EmergencySeverity.MODERATE);
        assertTrue(critical < high && high < moderate);
    }

    @Test
    @DisplayName("Every emergency severity ranks strictly ahead of PRIORITY and NORMAL patients")
    void allEmergenciesAheadOfNonEmergency() {
        for (EmergencySeverity s : EmergencySeverity.values()) {
            int rank = QueueService.resolveEmergencyPriorityRank(s);
            assertTrue(rank < QueuePriority.PRIORITY.getRank(), s + " must outrank PRIORITY");
            assertTrue(rank < QueuePriority.NORMAL.getRank(), s + " must outrank NORMAL");
        }
    }

    @Test
    @DisplayName("Null severity falls back to an emergency-level rank rather than NORMAL")
    void nullSeverityStillEmergency() {
        assertEquals(-1, QueueService.resolveEmergencyPriorityRank(null));
    }
}
