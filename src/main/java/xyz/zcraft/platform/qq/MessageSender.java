package xyz.zcraft.platform.qq;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.platform.AbstractApiMessageSender;
import xyz.zcraft.util.AccessToken;

import java.util.function.Supplier;

public class MessageSender extends AbstractApiMessageSender {
    private static final Logger LOG = LogManager.getLogger(MessageSender.class);

    public MessageSender(Supplier<AccessToken> tokenSupplier, CosUploadService cosUploadService) {
        super(new QqPrivateMessageApi(tokenSupplier, cosUploadService), LOG);
    }
}
