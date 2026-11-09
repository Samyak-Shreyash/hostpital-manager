package com.hms.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private static final long MILLIS = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();

    @Test
    void setsVersionVariantAndTimestamp() {
        UUID id = new UuidV7Generator(Clock.fixed(Instant.ofEpochMilli(MILLIS), ZoneOffset.UTC)).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(MILLIS);
    }

    @Test
    void strictlyIncreasingWithinOneMillisecondPastCounterOverflow() {
        UuidV7Generator generator = new UuidV7Generator(Clock.fixed(Instant.ofEpochMilli(MILLIS), ZoneOffset.UTC));

        UUID previous = generator.next();
        for (int i = 0; i < 10_000; i++) {
            UUID next = generator.next();
            assertThat(compareUnsigned(next, previous)).isPositive();
            previous = next;
        }
    }

    @Test
    void neverGoesBackwardsWhenTheClockDoes() {
        AtomicLong now = new AtomicLong(MILLIS);
        Clock clock = new Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
        UuidV7Generator generator = new UuidV7Generator(clock);

        UUID before = generator.next();
        now.set(MILLIS - 5_000);
        UUID after = generator.next();

        assertThat(compareUnsigned(after, before)).isPositive();
    }

    /** UUIDv7 order = unsigned byte order (UUID.compareTo is signed). */
    private static int compareUnsigned(UUID a, UUID b) {
        int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
