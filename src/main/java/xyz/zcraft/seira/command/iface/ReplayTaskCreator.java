package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.APIHelper;

@FunctionalInterface
public interface ReplayTaskCreator {
    APIHelper.ReplayTaskInfo create();
}
