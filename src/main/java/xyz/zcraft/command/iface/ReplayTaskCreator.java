package xyz.zcraft.command.iface;

import xyz.zcraft.api.APIHelper;

@FunctionalInterface
public interface ReplayTaskCreator {
    APIHelper.ReplayTaskInfo create();
}
