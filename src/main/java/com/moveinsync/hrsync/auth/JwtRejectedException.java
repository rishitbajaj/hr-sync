package com.moveinsync.hrsync.auth;

public class JwtRejectedException extends RuntimeException {

    public JwtRejectedException() {
        super("Invalid token");
    }
}
