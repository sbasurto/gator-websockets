/*
 * Copyright (C) 2021 Sergio Basurto Juárez
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package gator.websockets.helpers;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Assigns one ephemeral X25519 key pair to a bounded connection generation. */
public final class GatorWSKeyManager {
        private final int maxConnections;
        private final Duration maxAge;
        private Generation current;
        private int assigned;

        public GatorWSKeyManager(int maxConnections, Duration maxAge) {
                if(maxConnections < 1 || maxAge.isZero() || maxAge.isNegative()) {
                        throw new IllegalArgumentException("HPKE rotation limits must be positive");
                }
                this.maxConnections = maxConnections;
                this.maxAge = maxAge;
        }

        public synchronized Generation acquire() {
                Instant now = Instant.now();
                if(current == null || assigned >= maxConnections
                        || Duration.between(current.createdAt(), now).compareTo(maxAge) >= 0) {
                        current = new Generation(UUID.randomUUID().toString(), generateKeyPair(), now);
                        assigned = 0;
                }
                assigned++;
                return current;
        }

        private static KeyPair generateKeyPair() {
                try {
                        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
                } catch(GeneralSecurityException e) {
                        throw new IllegalStateException("X25519 is not available", e);
                }
        }

        public record Generation(String id, KeyPair keyPair, Instant createdAt) {}
}
