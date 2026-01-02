/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Copyright © 2024 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.dns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.BuildConfig;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local DNS proxy server that forwards DNS queries via DNS-over-HTTPS.
 * Listens on 127.0.0.1:5353 and forwards all queries to the configured DoH
 * endpoint.
 */
public class DnsProxyServer {
    private static final String TAG = "TrackerControl.DnsProxy";
    public static final int DNS_PROXY_PORT = 5353;
    public static final String DNS_PROXY_ADDRESS = "127.0.0.1";

    private static DnsProxyServer instance;

    private final Context context;
    private DatagramSocket serverSocket;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DnsProxyServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized DnsProxyServer getInstance(Context context) {
        if (instance == null) {
            instance = new DnsProxyServer(context);
        }
        return instance;
    }

    /**
     * Start the DNS proxy server if DoH is enabled.
     */
    public synchronized void start() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean dohEnabled = prefs.getBoolean("doh_enabled", false);

        if (!dohEnabled) {
            Log.i(TAG, "DoH not enabled, not starting DNS proxy");
            return;
        }

        if (running.get()) {
            Log.d(TAG, "DNS proxy already running");
            return;
        }

        try {
            serverSocket = new DatagramSocket(DNS_PROXY_PORT, InetAddress.getByName(DNS_PROXY_ADDRESS));
            serverSocket.setReuseAddress(true);
            running.set(true);

            // Use a thread pool for handling queries concurrently
            executor = Executors.newFixedThreadPool(4);

            // Start the main listener thread
            new Thread(this::runServer, "DnsProxyServer").start();

            Log.i(TAG, "DNS proxy server started on " + DNS_PROXY_ADDRESS + ":" + DNS_PROXY_PORT);
        } catch (SocketException e) {
            Log.e(TAG, "Failed to start DNS proxy: " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind DNS proxy: " + e.getMessage());
        }
    }

    /**
     * Stop the DNS proxy server.
     */
    public synchronized void stop() {
        if (!running.get()) {
            return;
        }

        running.set(false);

        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        Log.i(TAG, "DNS proxy server stopped");
    }

    /**
     * Check if the proxy server is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Main server loop - receives DNS queries and dispatches them to the thread
     * pool.
     */
    private void runServer() {
        byte[] buffer = new byte[4096]; // Increased for EDNS0 support

        while (running.get()) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(request);

                // Copy the data for the handler thread
                byte[] queryData = new byte[request.getLength()];
                System.arraycopy(request.getData(), 0, queryData, 0, request.getLength());

                final InetAddress clientAddress = request.getAddress();
                final int clientPort = request.getPort();

                // Handle the query in a separate thread
                executor.submit(() -> handleQuery(queryData, clientAddress, clientPort));

            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Error receiving DNS query: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Handle a single DNS query by forwarding it via DoH.
     */
    private void handleQuery(byte[] queryData, InetAddress clientAddress, int clientPort) {
        try {
            // Get the DoH client
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String endpoint = prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT);
            DnsOverHttpsClient dohClient = DnsOverHttpsClient.getInstance(endpoint);

            // Forward the query via DoH (the query is already in DNS wire format)
            byte[] responseData = dohClient.resolve(queryData);

            if (responseData != null) {
                // Send response back to client
                DatagramPacket response = new DatagramPacket(
                        responseData, responseData.length, clientAddress, clientPort);
                serverSocket.send(response);
                Log.d(TAG, "DoH query successful, response sent to " + clientAddress + ":" + clientPort);
            } else {
                Log.w(TAG, "DoH query returned null response");
                // Send SERVFAIL response
                sendServFailResponse(queryData, clientAddress, clientPort);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling DNS query: " + e.getMessage());
            // Try to send a SERVFAIL response
            try {
                sendServFailResponse(queryData, clientAddress, clientPort);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Send a SERVFAIL response to the client.
     */
    private void sendServFailResponse(byte[] queryData, InetAddress clientAddress, int clientPort)
            throws IOException {
        if (queryData.length < 12)
            return;

        // Copy the query and modify it to be a SERVFAIL response
        byte[] response = new byte[queryData.length];
        System.arraycopy(queryData, 0, response, 0, queryData.length);

        // Set QR bit (response) and RCODE to SERVFAIL (2)
        response[2] = (byte) ((queryData[2] | 0x80) & 0xFB); // QR=1, keep other flags
        response[3] = (byte) ((queryData[3] & 0xF0) | 0x02); // RCODE = SERVFAIL

        DatagramPacket packet = new DatagramPacket(response, response.length, clientAddress, clientPort);
        serverSocket.send(packet);
    }
}
