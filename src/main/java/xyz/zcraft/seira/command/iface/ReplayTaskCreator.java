package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.QqUploadRequest;

@FunctionalInterface
public interface ReplayTaskCreator {
    APIHelper.ReplayTaskInfo create(QqUploadRequest qqUpload);
}
