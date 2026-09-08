package xyz.zcraft.seira.command.target;

import xyz.zcraft.seira.data.UserRef;
import java.util.Objects;

public record TargetRequest(TargetKind kind, TargetQuery query, boolean remembered) {
    public TargetRequest {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(query);
    }

    public TargetRequest withUser(UserRef user) {
        return new TargetRequest(kind, query.withUser(user), remembered);
    }

    public TargetRequest withKind(TargetKind kind) {
        return new TargetRequest(kind, query, remembered);
    }

    public boolean isLocalScore() {
        return query instanceof TargetQuery.Reference ref && ref.target().isLocalScore();
    }
}
