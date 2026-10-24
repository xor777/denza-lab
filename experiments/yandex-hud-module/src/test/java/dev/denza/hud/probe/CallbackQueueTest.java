package dev.denza.hud.probe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The live receiver's callback queue on a held worker: answers kept, status shed, never refused by volume. */
public final class CallbackQueueTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        CallbackQueue queue = new CallbackQueue(worker::add, 8);
        List<String> ran = new ArrayList<>();
        for (int i = 0; i < 40; i++) { final int n = i; check(queue.enqueue(false, () -> ran.add("status" + n)), "status callback never refused by volume"); }
        check(queue.enqueue(true, () -> ran.add("ack")), "the ACK is accepted behind a full ring");
        for (int i = 40; i < 100; i++) { final int n = i; queue.enqueue(false, () -> ran.add("status" + n)); }
        check(worker.size() == 1, "one drain is posted, whatever the backlog");
        check(queue.dropped() == 92 && queue.depth() == 9, "the ring keeps its newest 8, drops the oldest, counted");
        while (!worker.isEmpty()) worker.poll().run();
        check(ran.get(0).equals("ack"), "essential work runs first after the block");
        check(ran.size() == 9 && ran.get(1).equals("status92") && ran.get(8).equals("status99"), "then the newest status callbacks in order");
        check(((Number) queue.stats().get("maxDepth")).longValue() == 9, "the backlog's depth is reported");

        for (int i = 0; i < 300; i++) queue.enqueue(true, () -> ran.add("essential"));
        int before = ran.size();
        worker.poll().run();
        check(ran.size() - before == CallbackQueue.BATCH && worker.size() == 1, "a long backlog yields to the worker in batches");
        while (!worker.isEmpty()) worker.poll().run();
        check(ran.size() - before == 300, "no essential callback is ever dropped");

        queue.enqueue(false, () -> { throw new IllegalStateException("bad task"); });
        queue.enqueue(true, () -> ran.add("after"));
        while (!worker.isEmpty()) worker.poll().run();
        check(ran.get(ran.size() - 1).equals("after") && ((Number) queue.stats().get("taskFailures")).longValue() == 1, "a failing task neither stops the drain nor goes unseen");

        queue.enqueue(false, () -> ran.add("late"));
        queue.close();
        while (!worker.isEmpty()) worker.poll().run();
        check(!ran.contains("late") && !queue.enqueue(true, () -> { }), "a closed queue runs nothing and refuses new work");

        CallbackQueue dead = new CallbackQueue(task -> false);
        check(!dead.enqueue(true, () -> { }) && ((Number) dead.stats().get("postFailures")).longValue() == 1, "a worker that takes no posts is reported, not hidden");
        System.out.println("CallbackQueueTest: " + checks + " checks passed");
    }
}
