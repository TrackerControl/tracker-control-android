/*
 * Copyright © 2024 TrackerControl
 *
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3
 * as published by the Free Software Foundation.
 */

package net.kollnig.missioncontrol.dns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * DNS-over-HTTPS (DoH) client for secure DNS resolution.
 * Sends DNS wire format queries to a DoH endpoint via HTTPS POST.
 */
public class DnsOverHttpsClient {
    private static final String TAG = "TrackerControl.DoH";
    private static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int WRITE_TIMEOUT_MS = 5000;
    private static DnsOverHttpsClient instance;
    private final OkHttpClient client;
    private final String endpoint;

    private DnsOverHttpsClient(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.endpoint = prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT);

        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Log.i(TAG, "DoH client initialized with endpoint: " + endpoint);
    }

    private DnsOverHttpsClient(String endpoint) {
        this.endpoint = endpoint;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Log.i(TAG, "DoH client initialized with endpoint: " + endpoint);
    }

    public static synchronized DnsOverHttpsClient getInstance(Context context) {
        if (instance == null) {
            instance = new DnsOverHttpsClient(context);
        }
        return instance;
    }

    /**
     * Get instance with specific endpoint (used by DnsProxyServer).
     */
    public static synchronized DnsOverHttpsClient getInstance(String endpoint) {
        if (instance == null || !instance.endpoint.equals(endpoint)) {
            instance = new DnsOverHttpsClient(endpoint);
        }
        return instance;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    /**
     * Resolve a DNS query using DoH.
     *
     * @param dnsQuery Raw DNS wire format query bytes
     * @return DNS wire format response bytes, or null on failure
     */
    @Nullable
    public byte[] resolve(@NonNull byte[] dnsQuery) {
        if (dnsQuery.length == 0) {
            Log.w(TAG, "Empty DNS query");
            return null;
        }

        try {
            RequestBody body = RequestBody.create(dnsQuery, DNS_MESSAGE);
            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Accept", "application/dns-message")
                    .header("Content-Type", "application/dns-message")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "DoH request failed with code: " + response.code());
                    return null;
                }

                ResponseBody responseBody = response.body();

                byte[] dnsResponse = responseBody.bytes();
                Log.d(TAG, "DoH response received: " + dnsResponse.length + " bytes");
                return dnsResponse;
            }
        } catch (IOException e) {
            Log.e(TAG, "DoH request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the current DoH endpoint URL.
     */
    public String getEndpoint() {
        return endpoint;
    }
}
