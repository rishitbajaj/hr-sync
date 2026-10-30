package com.moveinsync.hrsync.service;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;

public final class InfrastructureErrors {

    private InfrastructureErrors() {
    }

    public static boolean isInfrastructure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof QueryTimeoutException
                    || current instanceof TransientDataAccessResourceException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException) {
                return true;
            }
            if (current instanceof SQLException sql && connectionFailure(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean connectionFailure(String state) {
        return state != null && (state.startsWith("08") || state.equals("57014") || state.equals("57P01") || state.equals("53300"));
    }
}
