// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single instantaneous value that can go up or down (players online, heap bytes, TPS, …).
 *
 * <p>Backed by an {@link AtomicLong} holding the {@link Double#doubleToRawLongBits raw bits} of a
 * {@code double}, so writes from the tick thread and reads from the off-thread exposition sampler are
 * lock-free and consistent. A gauge stores a {@code double}; integral quantities (heap bytes, entity
 * counts) round-trip exactly for magnitudes below 2^53 and render without a fractional part.
 */
public final class Gauge {

    private final AtomicLong bits = new AtomicLong(Double.doubleToRawLongBits(0.0));

    /** Set the gauge to {@code value}. */
    public void set(double value) {
        bits.set(Double.doubleToRawLongBits(value));
    }

    /** Set the gauge to a {@code long} (convenience for counts / byte sizes). */
    public void set(long value) {
        set((double) value);
    }

    /** Add {@code delta} (may be negative) and return the new value. */
    public double add(double delta) {
        long prev, next;
        double result;
        do {
            prev = bits.get();
            result = Double.longBitsToDouble(prev) + delta;
            next = Double.doubleToRawLongBits(result);
        } while (!bits.compareAndSet(prev, next));
        return result;
    }

    /** Increment by one. */
    public void inc() {
        add(1.0);
    }

    /** Decrement by one. */
    public void dec() {
        add(-1.0);
    }

    /** The current value. */
    public double get() {
        return Double.longBitsToDouble(bits.get());
    }

    @Override
    public String toString() {
        return "Gauge(" + get() + ")";
    }
}
