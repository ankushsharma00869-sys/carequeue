package com.carequeue;

import com.carequeue.entity.QueuePriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuePriorityTest {

    @Test
    @DisplayName("Emergency priority rank must be 1, superseding Priority (2) and Normal (3)")
    void testPriorityHierarchy() {
        assertEquals(1, QueuePriority.CRITICAL.getRank());
        assertEquals(2, QueuePriority.PRIORITY.getRank());
        assertEquals(3, QueuePriority.NORMAL.getRank());

        assertTrue(QueuePriority.CRITICAL.getRank() < QueuePriority.NORMAL.getRank(),
            "CRITICAL priority rank must be strictly lower numerically than NORMAL for ASC ordering");
    }
}