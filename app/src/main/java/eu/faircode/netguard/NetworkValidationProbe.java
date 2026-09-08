/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

final class NetworkValidationProbe {
    private static final int VALIDATION_PORT = 443;
    private static final int VALIDATION_TIMEOUT_MS = 10_000;

    private NetworkValidationProbe() {
    }

    static void connect(SocketFactory socketFactory, String host) throws IOException {
        try (Socket socket = socketFactory.createSocket()) {
            socket.connect(new InetSocketAddress(host, VALIDATION_PORT), VALIDATION_TIMEOUT_MS);
        } catch (UncheckedIOException ex) {
            throw new IOException("Network validation failed", ex);
        }
    }
}
