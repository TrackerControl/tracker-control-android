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

import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;

/**
 * DNS-over-HTTPS (DoH) client for secure DNS resolution.
 * Sends DNS wire format queries to a DoH endpoint via HTTPS GET. The upstream
 * transaction ID is normalized to zero so semantically identical requests share
 * an HTTP cache entry, as recommended by RFC 8484. OkHttp then applies the DoH
 * server's Cache-Control policy without TrackerControl parsing DNS messages.
 */
public class DnsOverHttpsClient {
    private static final String TAG = "TrackerControl.DoH";
    private static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int WRITE_TIMEOUT_MS = 5000;
    private static final long HTTP_CACHE_MAX_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_GET_URL_LENGTH = 2048;
    private static final ExecutorService SHUTDOWN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DoH-shutdown");
        thread.setDaemon(true);
        return thread;
    });
    private static DnsOverHttpsClient instance;
    private static Cache responseCache;
    private final OkHttpClient client;
    private final String endpoint;

    private DnsOverHttpsClient(Context context, String endpoint) {
        this.endpoint = endpoint;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(2, 30, TimeUnit.SECONDS))
                .cache(getResponseCache(context))
                .retryOnConnectionFailure(true)
                .build();

        Log.i(TAG, "DoH client initialized with endpoint: " + endpoint);
    }

    public static synchronized DnsOverHttpsClient getInstance(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String endpoint = prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT);
        return getInstance(context, endpoint);
    }

    /**
     * Get instance with specific endpoint (used by DnsProxyServer).
     */
    public static synchronized DnsOverHttpsClient getInstance(Context context, String endpoint) {
        if (instance == null || !Objects.equals(instance.endpoint, endpoint)) {
            if (instance != null) {
                instance.shutdown();
            }
            instance = new DnsOverHttpsClient(context.getApplicationContext(), endpoint);
        }
        return instance;
    }

    private static synchronized Cache getResponseCache(Context context) {
        if (responseCache == null) {
            File directory = new File(context.getCacheDir(), "doh-http-cache");
            responseCache = new Cache(directory, HTTP_CACHE_MAX_BYTES);
        }
        return responseCache;
    }

    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
        }
        instance = null;
    }

    /**
     * Evict idle keep-alive connections from the current client, if any. Called on
     * screen-off so an idle pooled TLS socket cannot be reset by the server during
     * doze and wake the radio. In-flight requests are unaffected. No-op if no
     * client has been created yet.
     */
    public static synchronized void evictIdleConnections() {
        if (instance != null) {
            instance.evictIdle();
        }
    }

    private void evictIdle() {
        SHUTDOWN_EXECUTOR.execute(() -> client.connectionPool().evictAll());
    }

    /**
     * Shutdown the OkHttpClient and release resources.
     * Call this when DoH is disabled to prevent idle connections from draining
     * battery. Runs on a background thread to avoid NetworkOnMainThreadException
     * when closing sockets.
     */
    public void shutdown() {
        Log.i(TAG, "Shutting down DoH client");
        SHUTDOWN_EXECUTOR.execute(() -> {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            Cache cache = client.cache();
            if (cache != null) {
                try {
                    // The proxy is stopped on network changes, so do not carry
                    // answers into a different network environment.
                    cache.evictAll();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to clear DoH HTTP cache: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Resolve a DNS query using DoH.
     *
     * @param dnsQuery Raw DNS wire format query bytes
     * @return DNS wire format response bytes, or null on failure
     */
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 200;

    @Nullable
    public byte[] resolve(@NonNull byte[] dnsQuery) {
        if (dnsQuery.length < 12) {
            Log.w(TAG, "DNS query too short: " + dnsQuery.length + " bytes");
            return null;
        }

        Request request = buildRequest(endpoint, dnsQuery);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                Log.d(TAG, "DoH retry attempt " + attempt);
            }

            try {
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "DoH request failed with code: " + response.code());
                        if (response.code() < 500) return null; // Don't retry client errors
                        continue;
                    }

                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        Log.w(TAG, "DoH response body is null");
                        continue;
                    }

                    byte[] dnsResponse = responseBody.bytes();
                    if (dnsResponse.length < 12) {
                        Log.w(TAG, "DoH response too short: " + dnsResponse.length + " bytes");
                        continue;
                    }
                    dnsResponse = finalizeResponse(dnsResponse, response, dnsQuery);
                    Log.d(TAG, "DoH response received: " + dnsResponse.length + " bytes"
                            + (response.cacheResponse() == null ? "" : " (HTTP cache hit)"));
                    return dnsResponse;
                }
            } catch (IOException e) {
                Log.e(TAG, "DoH request failed: " + e.getMessage());
            }
        }

        return null;
    }

    static byte[] normalizeTransactionId(byte[] dnsQuery) {
        byte[] normalized = Arrays.copyOf(dnsQuery, dnsQuery.length);
        normalized[0] = 0;
        normalized[1] = 0;
        return normalized;
    }

    static HttpUrl buildRequestUrl(String endpoint, byte[] dnsQuery) {
        String encodedQuery = ByteString.of(normalizeTransactionId(dnsQuery)).base64Url();
        int padding = encodedQuery.indexOf('=');
        if (padding >= 0)
            encodedQuery = encodedQuery.substring(0, padding);
        HttpUrl base = HttpUrl.parse(endpoint);
        if (base == null)
            throw new IllegalArgumentException("Invalid DoH endpoint: " + endpoint);
        return base.newBuilder()
                .removeAllQueryParameters("dns")
                .addQueryParameter("dns", encodedQuery)
                .build();
    }

    static Request buildRequest(String endpoint, byte[] dnsQuery) {
        // buildRequestUrl normalizes the transaction ID internally, so pass the
        // raw query here and only re-normalize for the POST fallback body.
        HttpUrl requestUrl = buildRequestUrl(endpoint, dnsQuery);
        Request.Builder request = new Request.Builder()
                .url(requestUrl)
                .header("Accept", "application/dns-message");
        if (requestUrl.toString().length() <= MAX_GET_URL_LENGTH)
            request.get();
        else
            request.url(endpoint)
                    .post(RequestBody.create(normalizeTransactionId(dnsQuery), DNS_MESSAGE));
        return request.build();
    }

    /**
     * Restore the caller's transaction ID and, only when the HTTP response is not
     * fresh, subtract its age from record TTLs. Fresh responses (the common path)
     * are finished by patching the two ID bytes directly, keeping dnsjava off the
     * hot path. If TTL aging fails to round-trip the message, the answer is still
     * returned with its ID restored rather than dropping resolution entirely.
     */
    static byte[] finalizeResponse(byte[] dnsResponse, Response response, byte[] dnsQuery) {
        long ageSeconds = responseAgeSeconds(response);
        if (ageSeconds > 0) {
            try {
                return ageDnsResponse(dnsResponse, ageSeconds, dnsQuery);
            } catch (IOException e) {
                Log.w(TAG, "Failed to age DoH response, returning as-is: " + e.getMessage());
            }
        }
        restoreTransactionId(dnsResponse, dnsQuery);
        return dnsResponse;
    }

    static void restoreTransactionId(byte[] dnsResponse, byte[] dnsQuery) {
        dnsResponse[0] = dnsQuery[0];
        dnsResponse[1] = dnsQuery[1];
    }

    static long responseAgeSeconds(Response response) {
        long residentMillis = Math.max(0,
                System.currentTimeMillis() - response.receivedResponseAtMillis());
        long upstreamAge = 0;
        String age = response.header("Age");
        if (age != null) {
            try {
                upstreamAge = Math.max(0, Long.parseLong(age));
            } catch (NumberFormatException ignored) {
            }
        }
        long residentAge = residentMillis / 1000L;
        return upstreamAge > Long.MAX_VALUE - residentAge
                ? Long.MAX_VALUE
                : upstreamAge + residentAge;
    }

    static byte[] ageDnsResponse(byte[] dnsResponse, long ageSeconds, byte[] dnsQuery)
            throws IOException {
        Message message = new Message(dnsResponse);
        message.getHeader().setID(((dnsQuery[0] & 0xFF) << 8) | (dnsQuery[1] & 0xFF));

        if (ageSeconds > 0) {
            for (int section : new int[]{Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL}) {
                List<Record> records = message.getSection(section);
                message.removeAllRecords(section);
                for (Record record : records) {
                    Record aged = record;
                    // OPT's TTL-shaped field contains EDNS metadata, not a cache TTL.
                    if (record.getType() != Type.OPT && record.getTTL() > 0) {
                        long ttl = ageSeconds >= record.getTTL()
                                ? 0
                                : record.getTTL() - ageSeconds;
                        aged = Record.newRecord(record.getName(), record.getType(),
                                record.getDClass(), ttl, record.rdataToWireCanonical());
                        if (aged == null)
                            throw new IOException("Failed to rebuild aged DNS record");
                    }
                    message.addRecord(aged, section);
                }
            }
        }
        return message.toWire();
    }

    /**
     * Get the current DoH endpoint URL.
     */
    public String getEndpoint() {
        return endpoint;
    }
}
