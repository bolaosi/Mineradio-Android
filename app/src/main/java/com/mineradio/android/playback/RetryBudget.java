package com.mineradio.android.playback;

import java.util.HashSet;
import java.util.Set;

/** One automatic refresh per item until explicit user retry or successful playback. */
public final class RetryBudget {
    private final Set<String> attempted = new HashSet<>();
    public synchronized boolean consume(String id) { return attempted.add(id); }
    public synchronized void reset(String id) { attempted.remove(id); }
    public synchronized void clear() { attempted.clear(); }
}
