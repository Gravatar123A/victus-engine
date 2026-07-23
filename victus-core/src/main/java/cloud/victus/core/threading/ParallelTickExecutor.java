// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.threading;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A barrier-synchronized "run these independent units, then join" primitive for the {@code PARALLEL}
 * threading tier — the correctness-critical core of parallel world/region ticking, kept
 * <b>Paper-independent</b> so it is unit-testable offline (see {@code ParallelTickSelfTest}).
 *
 * <p>Contract (this is what makes it safe to drop into a tick loop):
 * <ul>
 *   <li><b>Barrier:</b> {@link #runAll(List)} does not return until <em>every</em> unit has finished, so
 *       the caller (the main tick thread) resumes with a consistent state — no unit is still running when
 *       the global/finish phase begins. Built on {@link ThreadPoolExecutor#invokeAll} which blocks on all.</li>
 *   <li><b>Exception propagation:</b> if any unit throws, the first failure is rethrown on the caller
 *       thread after the barrier (so the server's existing "exception ticking world" crash path still
 *       fires) — a worker never swallows a tick error.</li>
 *   <li><b>Serial fallback:</b> a null/absent pool, a single unit, or {@code enabled=false} runs the units
 *       inline on the caller thread — identical behaviour to the classic serial loop.</li>
 * </ul>
 *
 * <p><b>This class does not make world ticking thread-safe.</b> It only provides the fan-out/join. Whether
 * the units are actually independent (no shared mutable state, no cross-unit interaction mid-tick) is the
 * caller's responsibility — see docs/phase-4 for the (unfinished) world-tick safety analysis.
 */
public final class ParallelTickExecutor {

    private volatile ThreadPoolExecutor pool;
    private volatile boolean enabled;
    private final int threads;

    /** @param threads worker threads (&ge;1); the pool is created lazily on first {@link #enable()}. */
    public ParallelTickExecutor(final int threads) {
        this.threads = Math.max(1, threads);
    }

    /** Build the worker pool (idempotent). Daemon, low-priority, bounded queue sized to the pool. */
    public synchronized void enable() {
        if (pool != null) {
            enabled = true;
            return;
        }
        final AtomicInteger n = new AtomicInteger(1);
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(threads * 4, 16)),
                r -> {
                    Thread t = new Thread(r, "Victus Parallel Tick Worker #" + n.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()); // overflow runs on the caller — still correct, just serial
        this.pool = ex;
        this.enabled = true;
    }

    /** Disable + tear down the pool (units then run serially). Safe to call repeatedly. */
    public synchronized void disable() {
        enabled = false;
        ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
    }

    /** True when a live pool is present and parallel dispatch is on. */
    public boolean isParallel() {
        ThreadPoolExecutor p = pool;
        return enabled && p != null && !p.isShutdown();
    }

    /**
     * Run every unit and return only once all have completed (the barrier). Units run in parallel when
     * {@link #isParallel()}, else serially on the caller thread. Rethrows the first unit failure.
     */
    public void runAll(final List<Runnable> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        ThreadPoolExecutor p = pool;
        if (!enabled || p == null || p.isShutdown() || units.size() == 1) {
            runSerial(units); // fast path / fallback: identical to the classic serial loop
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(units.size());
        for (final Runnable u : units) {
            tasks.add(() -> { u.run(); return null; });
        }
        List<Future<Void>> futures;
        try {
            futures = p.invokeAll(tasks); // BARRIER — blocks until all units finish (or fail)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Do NOT serial-retry (some units already ran — would double-tick). Surface as a tick failure.
            throw new RuntimeException("parallel tick interrupted", ie);
        }
        Throwable first = null;
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ee) {
                if (first == null) first = ee.getCause() != null ? ee.getCause() : ee;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (first == null) first = ie;
            }
        }
        if (first != null) {
            // Preserve the original throwable so the server's crash-report path reports the real cause.
            if (first instanceof RuntimeException re) throw re;
            if (first instanceof Error er) throw er;
            throw new RuntimeException("parallel tick unit failed", first);
        }
    }

    private static void runSerial(final List<Runnable> units) {
        for (final Runnable u : units) {
            u.run();
        }
    }
}
