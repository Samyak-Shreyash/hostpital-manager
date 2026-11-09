package com.hms.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562) generator, ADR-0004. 48-bit Unix-millisecond timestamp, a 12-bit counter in
 * {@code rand_a} (RFC 9562 method 1) so ids increase strictly within a millisecond, and 62 random bits
 * in {@code rand_b}. If the clock goes backwards or the counter overflows, the last timestamp is reused
 * or advanced instead, so ids from one generator never go backwards.
 */
public final class UuidV7Generator {

    private static final int COUNTER_MAX = 0xFFF;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private long lastMillis = -1;
    private int counter;

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public synchronized UUID next() {
        long now = clock.millis();
        if (now > lastMillis) {
            lastMillis = now;
            // Start in the lower half so a burst within one millisecond rarely overflows.
            counter = random.nextInt(COUNTER_MAX / 2);
        } else if (++counter > COUNTER_MAX) {
            lastMillis++;
            counter = 0;
        }
        long msb = (lastMillis << 16) | 0x7000L | counter;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
