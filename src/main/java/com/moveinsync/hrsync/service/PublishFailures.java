package com.moveinsync.hrsync.service;

import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

import java.util.concurrent.TimeoutException;

public final class PublishFailures {

    private PublishFailures() {
    }

    public static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (isPermanentType(current)) {
                return false;
            }
            if (current instanceof TimeoutException || current instanceof RetriableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isKafka(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof KafkaPublishException || current instanceof org.apache.kafka.common.KafkaException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isPermanentType(Throwable failure) {
        return failure instanceof RecordTooLargeException
                || failure instanceof RecordBatchTooLargeException
                || failure instanceof SerializationException
                || failure instanceof AuthorizationException
                || failure instanceof AuthenticationException;
    }
}
