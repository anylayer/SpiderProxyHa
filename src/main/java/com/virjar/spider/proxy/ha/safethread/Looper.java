package com.virjar.spider.proxy.ha.safethread;


import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * 单线程事件循环模型，用来避免一致性问题
 */
@Slf4j
public class Looper {
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();

    private final LoopThread loopThread;
    /**
     * 让looper拥有延时任务的能力
     */
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Looper(String looperName) {
        loopThread = new LoopThread(looperName);
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public void post(Runnable runnable) {
        taskQueue.add(runnable);
    }

    public void postDelay(Runnable runnable, long delay) {
        if (delay <= 0) {
            post(runnable);
            return;
        }
        scheduler.schedule(() -> post(runnable), delay, TimeUnit.MILLISECONDS);
    }

    public FixRateScheduleHandle scheduleWithRate(Runnable runnable, long rate) {
        FixRateScheduleHandle fixRateScheduleHandle = new FixRateScheduleHandle(runnable, rate);
        if (rate > 0) {
            post(runnable);
        }
        postDelay(fixRateScheduleHandle, rate);
        return fixRateScheduleHandle;
    }

    public class FixRateScheduleHandle implements Runnable {
        private final Runnable runnable;
        private final long rate;
        private boolean running;


        FixRateScheduleHandle(Runnable runnable, long rate) {
            this.runnable = runnable;
            this.running = true;
            this.rate = rate;
        }

        public void cancel() {
            this.running = false;
        }

        @Override
        public void run() {
            if (running && rate > 0) {
                postDelay(this, rate);
            }
            runnable.run();
        }

    }

    public boolean inLooper() {
        return Thread.currentThread().equals(loopThread);
    }

    public void checkLooper() {
        if (!inLooper()) {
            throw new IllegalStateException("run task not in looper");
        }
    }

    private class LoopThread extends Thread {
        LoopThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    taskQueue.take().run();
                } catch (InterruptedException interruptedException) {
                    return;
                } catch (Throwable throwable) {
                    log.error("group event loop error", throwable);
                }
            }
        }
    }
}
