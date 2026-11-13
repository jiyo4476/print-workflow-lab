package dev.yjimmy.printlab.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A deterministic {@link Clock} that starts at a fixed instant and moves forward by a fixed tick
 * every time it is read, so consecutive timestamps (a step's start and finish) differ predictably.
 */
public final class TickingClock extends Clock {

    /** 2026-01-01T00:00:00Z: an arbitrary, fixed start for tests. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

    private final Duration tick;
    private Instant next;

    public TickingClock(Instant start, Duration tick) {
        this.next = start;
        this.tick = tick;
    }

    /** Starts at {@link #DEFAULT_START} and ticks one second per read. */
    public static TickingClock oneSecondTicks() {
        return new TickingClock(DEFAULT_START, Duration.ofSeconds(1));
    }

    @Override
    public Instant instant() {
        Instant now = next;
        next = next.plus(tick);
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("TickingClock is always UTC");
    }
}
