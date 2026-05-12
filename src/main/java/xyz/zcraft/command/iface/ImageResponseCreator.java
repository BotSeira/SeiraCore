package xyz.zcraft.command.iface;

import xyz.zcraft.api.Response;

@FunctionalInterface
public interface ImageResponseCreator {
    Response<Void> create();
}
