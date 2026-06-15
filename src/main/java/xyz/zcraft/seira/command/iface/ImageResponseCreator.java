package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.data.Base64Bytes;
import xyz.zcraft.seira.api.data.Response;

@FunctionalInterface
public interface ImageResponseCreator {
    Response<Base64Bytes> create();
}
