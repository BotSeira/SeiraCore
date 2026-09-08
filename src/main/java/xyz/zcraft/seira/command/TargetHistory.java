package xyz.zcraft.seira.command;

import xyz.zcraft.seira.command.target.TargetMemory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

/** Stores immutable caller snapshots. Failed updates leave the previous snapshot intact. */
public final class TargetHistory {
    private final ConcurrentMap<String, TargetMemory> memories = new ConcurrentHashMap<>();

    public TargetMemory get(String caller) {
        return memories.getOrDefault(caller, TargetMemory.EMPTY);
    }

    public TargetMemory update(String caller, UnaryOperator<TargetMemory> update) {
        return memories.compute(caller, (_, previous) ->
                update.apply(previous == null ? TargetMemory.EMPTY : previous));
    }
}
