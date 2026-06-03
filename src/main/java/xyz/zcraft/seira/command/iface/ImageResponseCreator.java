package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.Base64Bytes;

@FunctionalInterface
public interface ImageResponseCreator {
    Response<Base64Bytes> create();
}
