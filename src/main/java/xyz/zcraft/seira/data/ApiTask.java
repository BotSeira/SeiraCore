package xyz.zcraft.seira.data;

import xyz.zcraft.seira.command.iface.ApiTaskExecutor;
import xyz.zcraft.seira.command.iface.ApiTaskFinalizer;
import xyz.zcraft.seira.command.iface.ApiTaskPostProcessor;

public record ApiTask(
        String requestType,
        ApiTaskExecutor executor,
        ApiTaskPostProcessor postProcessor,
        ApiTaskFinalizer finalizer,
        boolean completeStatsAfterExecutor
) {
}
