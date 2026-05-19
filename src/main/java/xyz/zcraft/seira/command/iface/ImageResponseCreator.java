package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.Response;
import xyz.zcraft.seira.data.Base64Bytes;

@FunctionalInterface
public interface ImageResponseCreator {
    Response<Base64Bytes> create();
}
