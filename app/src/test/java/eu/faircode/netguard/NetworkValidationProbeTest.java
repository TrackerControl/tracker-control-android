/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

import org.junit.Test;

public class NetworkValidationProbeTest {
    private static final String TEST_HOST = "127.0.0.1";

    @Test
    public void uncheckedSocketCreationFailureIsNormalised() {
        IOException cause = new IOException("creation failed");

        try {
            NetworkValidationProbe.connect(new StubSocketFactory(new UncheckedIOException(cause)), TEST_HOST);
            fail("Expected IOException");
        } catch (IOException ex) {
            assertEquals("Network validation failed", ex.getMessage());
            assertTrue(ex.getCause() instanceof UncheckedIOException);
            assertSame(cause, ex.getCause().getCause());
        }
    }

    @Test
    public void checkedSocketCreationFailureIsPreserved() {
        IOException expected = new IOException("creation failed");

        try {
            NetworkValidationProbe.connect(new StubSocketFactory(expected), TEST_HOST);
            fail("Expected IOException");
        } catch (IOException ex) {
            assertSame(expected, ex);
        }
    }

    @Test
    public void successClosesSocketAndUsesValidationEndpointAndTimeout() throws IOException {
        TrackingSocket socket = new TrackingSocket();

        NetworkValidationProbe.connect(new StubSocketFactory(socket), TEST_HOST);

        assertTrue(socket.closed);
        InetSocketAddress endpoint = (InetSocketAddress) socket.endpoint;
        assertEquals(TEST_HOST, endpoint.getHostString());
        assertEquals(443, endpoint.getPort());
        assertEquals(10_000, socket.timeoutMs);
    }

    @Test
    public void uncheckedConnectFailureIsNormalisedAndSocketClosed() {
        TrackingSocket socket = new TrackingSocket();
        IOException cause = new IOException("connect failed");
        socket.runtimeConnectFailure = new UncheckedIOException(cause);

        try {
            NetworkValidationProbe.connect(new StubSocketFactory(socket), TEST_HOST);
            fail("Expected IOException");
        } catch (IOException ex) {
            assertEquals("Network validation failed", ex.getMessage());
            assertTrue(ex.getCause() instanceof UncheckedIOException);
            assertSame(cause, ex.getCause().getCause());
        }

        assertTrue(socket.closed);
    }

    @Test
    public void checkedConnectFailureClosesSocket() {
        TrackingSocket socket = new TrackingSocket();
        IOException expected = new IOException("connect failed");
        socket.checkedConnectFailure = expected;

        try {
            NetworkValidationProbe.connect(new StubSocketFactory(socket), TEST_HOST);
            fail("Expected IOException");
        } catch (IOException ex) {
            assertSame(expected, ex);
        }

        assertTrue(socket.closed);
    }

    @Test
    public void unexpectedRuntimeFailureIsNotSwallowedAndSocketClosed() {
        TrackingSocket socket = new TrackingSocket();
        IllegalStateException expected = new IllegalStateException("unexpected");
        socket.runtimeConnectFailure = expected;

        try {
            NetworkValidationProbe.connect(new StubSocketFactory(socket), TEST_HOST);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException ex) {
            assertSame(expected, ex);
        } catch (IOException ex) {
            fail("Unexpected IOException: " + ex);
        }

        assertTrue(socket.closed);
    }

    private static final class StubSocketFactory extends SocketFactory {
        private final Socket socket;
        private final IOException checkedFailure;
        private final RuntimeException runtimeFailure;

        private StubSocketFactory(Socket socket) {
            this.socket = socket;
            this.checkedFailure = null;
            this.runtimeFailure = null;
        }

        private StubSocketFactory(IOException checkedFailure) {
            this.socket = null;
            this.checkedFailure = checkedFailure;
            this.runtimeFailure = null;
        }

        private StubSocketFactory(RuntimeException runtimeFailure) {
            this.socket = null;
            this.checkedFailure = null;
            this.runtimeFailure = runtimeFailure;
        }

        @Override
        public Socket createSocket() throws IOException {
            if (checkedFailure != null)
                throw checkedFailure;
            if (runtimeFailure != null)
                throw runtimeFailure;
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TrackingSocket extends Socket {
        private boolean closed;
        private int timeoutMs;
        private SocketAddress endpoint;
        private IOException checkedConnectFailure;
        private RuntimeException runtimeConnectFailure;

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            this.endpoint = endpoint;
            this.timeoutMs = timeout;
            if (checkedConnectFailure != null)
                throw checkedConnectFailure;
            if (runtimeConnectFailure != null)
                throw runtimeConnectFailure;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
