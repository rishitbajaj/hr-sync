package com.moveinsync.hrsync.domain;

public record Decision(Kind kind, ReasonCode reason, HrEmployee state) {

    public enum Kind {
        APPLY,
        IGNORED
    }

    public static Decision apply(HrEmployee state) {
        return new Decision(Kind.APPLY, null, state);
    }

    public static Decision ignored(ReasonCode reason) {
        return new Decision(Kind.IGNORED, reason, null);
    }
}
