package xyz.zcraft.seira.data;

import lombok.Getter;

public class UserRef {
    private UserRef() {}

    public static class ByUid extends UserRef {
        @Getter
        private final long uid;

        public ByUid(long uid) {
            this.uid = uid;
        }
    }

    public static class ByUsername extends UserRef {
        @Getter
        private final String username;

        public ByUsername(String username) {
            this.username = username;
        }
    }
}
