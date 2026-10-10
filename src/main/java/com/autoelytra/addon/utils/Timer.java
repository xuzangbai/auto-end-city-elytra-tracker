package com.autoelytra.addon.utils;

/** Simple millisecond-precision timer. */
public class Timer {
    private long ms;

    public Timer() {
        this.ms = System.currentTimeMillis();
    }

    public void reset() {
        this.ms = System.currentTimeMillis();
    }

    /** Set the timer so that ms milliseconds appear to have already elapsed. */
    public void setMs(long ms) {
        this.ms = System.currentTimeMillis() - ms;
    }

    public boolean passedMs(double delay) {
        return System.currentTimeMillis() - this.ms >= delay;
    }
}
