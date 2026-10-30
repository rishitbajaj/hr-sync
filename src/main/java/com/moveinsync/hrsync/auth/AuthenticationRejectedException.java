package com.moveinsync.hrsync.auth;

public class AuthenticationRejectedException extends RuntimeException {

    public AuthenticationRejectedException() {
        super("Invalid username or password");
    }
}
