// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.threading;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free self-test for {@link ParallelTickExecutor} (no JUnit; runs offline with just the JDK):
 * <pre>
 *   javac -d out $(find src -name '*.java')
 *   java -cp out cloud.victus.core.threading.ParallelTickSelfTest
 * </pre>
 * Covers the barrier (all units done before return), exception propagation, serial fallback, and the
 * enable/disable lifecycle.
 */
public final class ParallelTickSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // 1. null / empty units are a no-op
        ParallelTickExecutor ex = new ParallelTickExecutor(4);
        ex.runAll(null);
        ex.runAll(new ArrayList<>());
        check("null/empty units is a harmless no-op", true);

        // 2. serial fallback while disabled: all units run, on the caller thread
        AtomicInteger ran = new AtomicInteger();
        long caller = Thread.currentThread().getId();
        AtomicInteger offThread = new AtomicInteger();
        List<Runnable> units = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            units.add(() -> {
                ran.incrementAndGet();
                if (Thread.currentThread().getId() != caller) offThread.incrementAndGet();
            });
        }
        check("disabled: not parallel", !ex.isParallel());
        ex.runAll(units);
        check("disabled: all 8 units ran (serial)", ran.get() == 8);
        check("disabled: none ran off the caller thread", offThread.get() == 0);

        // 3. enable -> parallel: all units run, barrier holds (count is complete on return)
        ex.enable();
        check("enabled: isParallel", ex.isParallel());
        ran.set(0);
        offThread.set(0);
        ex.runAll(units);
        check("enabled: barrier — all 8 units done when runAll returns", ran.get() == 8);
        check("enabled: at least one unit ran off the caller thread", offThread.get() >= 1);

        // 4. barrier under contention: a shared slot must be fully populated on return (no straggler)
        final int N = 64;
        int[] slots = new int[N];
        List<Runnable> writers = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            final int idx = i;
            writers.add(() -> {
                try { Thread.sleep((idx % 5)); } catch (InterruptedException ignored) {}
                slots[idx] = idx + 1;
            });
        }
        ex.runAll(writers);
        boolean allWritten = true;
        for (int i = 0; i < N; i++) if (slots[i] != i + 1) allWritten = false;
        check("barrier: every slot written before runAll returned (no straggler)", allWritten);

        // 5. exception propagation: a failing unit rethrows on the caller
        boolean threw = false;
        try {
            List<Runnable> boom = new ArrayList<>();
            boom.add(() -> {});
            boom.add(() -> { throw new IllegalStateException("boom"); });
            boom.add(() -> {});
            ex.runAll(boom);
        } catch (RuntimeException e) {
            threw = containsMessage(e, "boom");
        }
        check("a unit exception propagates to the caller", threw);

        // 6. single unit -> serial path even when enabled (runs on caller)
        ran.set(0); offThread.set(0);
        List<Runnable> one = new ArrayList<>();
        one.add(() -> { ran.incrementAndGet(); if (Thread.currentThread().getId() != caller) offThread.incrementAndGet(); });
        ex.runAll(one);
        check("single unit runs serially on the caller", ran.get() == 1 && offThread.get() == 0);

        // 7. disable tears down; back to serial
        ex.disable();
        check("disabled after teardown: not parallel", !ex.isParallel());
        ran.set(0);
        ex.runAll(units);
        check("post-disable: units still all run (serial)", ran.get() == 8);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static boolean containsMessage(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(needle)) return true;
        }
        return false;
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
