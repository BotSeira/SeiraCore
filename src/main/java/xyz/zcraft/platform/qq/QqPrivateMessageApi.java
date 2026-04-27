package xyz.zcraft.platform.qq;

import xyz.zcraft.data.FileInfo;
import xyz.zcraft.data.Message;
import xyz.zcraft.platform.PlatformPrivateMessageApi;
import xyz.zcraft.util.AccessToken;

import java.util.function.Supplier;

public class QqPrivateMessageApi implements PlatformPrivateMessageApi {
    private final Supplier<AccessToken> tokenSupplier;
    private final CosUploadService cosUploadService;

    public QqPrivateMessageApi(Supplier<AccessToken> tokenSupplier, CosUploadService cosUploadService) {
        this.tokenSupplier = tokenSupplier;
        this.cosUploadService = cosUploadService;
    }

    @Override
    public void sendPrivateMessage(String userId, Message message) {
        NetworkHelper.sendPrivateMessage(tokenSupplier.get(), userId, message);
    }

    @Override
    public FileInfo uploadPrivateMedia(String userId, int fileType, String url) {
        String cosUrl = cosUploadService.uploadFromUrl(url, fileType);
        return NetworkHelper.uploadPrivateMedia(tokenSupplier.get(), userId, fileType, cosUrl);
    }

    @Override
    public FileInfo uploadPrivateMediaBase64(String userId, int fileType, String base64Str) {
        return NetworkHelper.uploadPrivateMediaBase64(tokenSupplier.get(), userId, fileType, base64Str);
    }
}

