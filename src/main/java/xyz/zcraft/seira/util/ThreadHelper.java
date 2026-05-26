package xyz.zcraft.seira.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class ThreadHelper {
    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    public static void run(Runnable runnable) {
        executor.execute(runnable);
    }

    public static void close() {
        executor.shutdown();
    }
}
