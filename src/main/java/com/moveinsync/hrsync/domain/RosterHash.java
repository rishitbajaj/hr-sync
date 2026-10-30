package com.moveinsync.hrsync.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RosterHash {

    private RosterHash() {
    }

    public static byte[] record(
            String employeeId,
            long version,
            String name,
            String email,
            String phone,
            String manager,
            String officeCode,
            String shift
    ) {
        MessageDigest digest = sha256();
        write(digest, Text.trimToNull(employeeId));
        writeLong(digest, version);
        write(digest, Text.trimToNull(name));
        write(digest, Text.trimToNull(email));
        write(digest, Text.trimToNull(phone));
        write(digest, Text.trimToNull(manager));
        write(digest, Text.trimToNull(officeCode));
        write(digest, Text.trimToNull(shift));
        return digest.digest();
    }

    public static int bucket(String employeeId, int bucketCount) {
        byte[] hash = sha256().digest(bytes(Text.trimToNull(employeeId)));
        int mixed = ((hash[0] & 0xff) << 24)
                | ((hash[1] & 0xff) << 16)
                | ((hash[2] & 0xff) << 8)
                | (hash[3] & 0xff);
        return Math.floorMod(mixed, bucketCount);
    }

    private static void write(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeInt(digest, encoded.length);
        digest.update(encoded);
    }

    private static void writeLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
