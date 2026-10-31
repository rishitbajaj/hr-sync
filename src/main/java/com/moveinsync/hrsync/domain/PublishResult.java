package com.moveinsync.hrsync.domain;

public record PublishResult(ChangeNotice notice, RuntimeException failure) {

    public boolean acked() {
        return failure == null;
    }
}
