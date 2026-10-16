package com.carequeue.entity;

public enum QueuePriority {
    CRITICAL(1),
    PRIORITY(2),
    NORMAL(3);

    private final int rank;

    QueuePriority(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }
}
