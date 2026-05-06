package xyz.zcraft.data;

import xyz.zcraft.command.iface.ApiTaskExecutor;
import xyz.zcraft.command.iface.ApiTaskFinalizer;
import xyz.zcraft.command.iface.ApiTaskPostProcessor;

public record ApiTask(
        String requestType,
        ApiTaskExecutor executor,
        ApiTaskPostProcessor postProcessor,
        ApiTaskFinalizer finalizer,
        boolean completeStatsAfterExecutor
) {
}
