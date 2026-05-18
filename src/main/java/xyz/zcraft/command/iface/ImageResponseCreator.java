package xyz.zcraft.command.iface;

import xyz.zcraft.api.Response;
import xyz.zcraft.data.Base64Bytes;

@FunctionalInterface
public interface ImageResponseCreator {
    Response<Base64Bytes> create();
}
