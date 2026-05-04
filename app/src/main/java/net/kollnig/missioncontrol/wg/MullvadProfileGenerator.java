package net.kollnig.missioncontrol.wg;

import android.text.TextUtils;

import net.kollnig.missioncontrol.wgbridge.Wgbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MullvadProfileGenerator {
    private static final String API = "https://api.mullvad.net";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_DNS = "10.64.0.1, fc00:bbbb:bbbb:bb01::1";
    private static final int DEFAULT_PORT = 51820;

    private final OkHttpClient client = new OkHttpClient();
    private final SecureRandom random = new SecureRandom();

    public static class CountryOption {
        public final String code;
        public final String name;

        public CountryOption(String code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class GeneratedProfile {
        public final String name;
        public final String config;
        public final String accountNumber;
        public final String countryCode;
        public final String countryName;
        public final String relayHostname;
        public final String deviceId;

        public GeneratedProfile(String name, String config, String accountNumber,
                                String countryCode, String countryName, String relayHostname,
                                String deviceId) {
            this.name = name;
            this.config = config;
            this.accountNumber = accountNumber;
            this.countryCode = normalizeCountry(countryCode);
            this.countryName = countryName == null ? "" : countryName;
            this.relayHostname = relayHostname == null ? "" : relayHostname;
            this.deviceId = deviceId == null ? "" : deviceId;
        }
    }

    public static class ApiRejectedException extends IOException {
        ApiRejectedException(String message) {
            super(message);
        }

        public boolean isPublicKeyInUse() {
            String message = getMessage();
            return message != null && message.contains("PUBKEY_IN_USE");
        }
    }

    private static class Relay {
        String hostname;
        String countryCode;
        String countryName;
        String provider;
        String ipv4;
        String publicKey;
        int speed;
        boolean stboot;
    }

    public List<CountryOption> fetchCountryOptions() throws Exception {
        Map<String, String> countries = new LinkedHashMap<>();
        for (Relay relay : fetchRelays()) {
            countries.put(relay.countryCode, relay.countryName);
        }

        List<CountryOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : countries.entrySet())
            options.add(new CountryOption(entry.getKey(), entry.getValue()));
        Collections.sort(options, Comparator.comparing(o -> o.name));
        return options;
    }

    public GeneratedProfile generate(String accountNumber, String requestedCountryCode) throws Exception {
        return generate(accountNumber, requestedCountryCode, null);
    }

    public GeneratedProfile generate(String accountNumber, String requestedCountryCode, String reusableConfig) throws Exception {
        String account = accountNumber == null ? "" : accountNumber.trim();
        if (account.isEmpty())
            throw new IllegalArgumentException("Mullvad account number is required");

        WgConfig reusable = parseReusableConfig(reusableConfig);
        String privateKey;
        JSONObject device;
        if (reusable == null) {
            privateKey = Wgbridge.generatePrivateKey();
            String publicKey = Wgbridge.publicKey(privateKey);
            String token = fetchWebToken(account);
            device = createDevice(token, publicKey);
        } else {
            privateKey = reusable.getPrivateKey();
            device = deviceFromConfig(reusable);
        }
        Relay relay = chooseRelay(fetchRelays(), requestedCountryCode);

        String config = buildConfig(privateKey, device, relay);
        return new GeneratedProfile("Mullvad - " + relay.countryName, config, account,
                relay.countryCode, relay.countryName, relay.hostname, device.optString("id", ""));
    }

    public String findDeviceIdForPubkey(String accountNumber, String publicKey) throws Exception {
        if (TextUtils.isEmpty(publicKey))
            return "";
        String token = fetchWebToken(accountNumber);
        for (JSONObject device : listDevices(token))
            if (publicKey.equals(device.optString("pubkey")))
                return device.optString("id", "");
        return "";
    }

    public boolean deviceHasPubkey(String accountNumber, String deviceId, String publicKey)
            throws Exception {
        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(publicKey))
            return false;
        String token = fetchWebToken(accountNumber);
        for (JSONObject device : listDevices(token))
            if (deviceId.equals(device.optString("id")))
                return publicKey.equals(device.optString("pubkey"));
        return false;
    }

    public void rotateDevicePubkey(String accountNumber, String deviceId, String publicKey)
            throws Exception {
        if (TextUtils.isEmpty(deviceId))
            throw new IllegalArgumentException("Mullvad device id is required");
        if (TextUtils.isEmpty(publicKey))
            throw new IllegalArgumentException("Mullvad public key is required");

        String token = fetchWebToken(accountNumber);
        JSONObject body = new JSONObject();
        body.put("pubkey", publicKey);
        JSONObject device = requestJson("PUT",
                API + "/accounts/v1/devices/" + deviceId + "/pubkey", token, body);
        if (!publicKey.equals(device.optString("pubkey", "")))
            throw new IOException("Mullvad did not confirm the new public key");
    }

    private WgConfig parseReusableConfig(String reusableConfig) {
        if (TextUtils.isEmpty(reusableConfig))
            return null;
        try {
            WgConfig config = WgConfigParser.INSTANCE.parse(reusableConfig);
            if (config.getAddress().isEmpty())
                return null;
            return config;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private JSONObject deviceFromConfig(WgConfig config) throws Exception {
        JSONObject device = new JSONObject();
        for (String address : config.getAddress()) {
            String ip = address.trim();
            if (ip.contains(":"))
                device.put("ipv6_address", ip);
            else
                device.put("ipv4_address", ip);
        }
        if (TextUtils.isEmpty(device.optString("ipv4_address")) &&
                TextUtils.isEmpty(device.optString("ipv6_address")))
            throw new IllegalArgumentException("Reusable Mullvad profile has no interface address");
        device.put("name", "existing");
        return device;
    }

    private String fetchWebToken(String accountNumber) throws Exception {
        JSONObject body = new JSONObject();
        body.put("account_number", accountNumber);

        JSONObject response = postJson(API + "/auth/v1/webtoken", null, body);
        String token = response.optString("access_token", "");
        if (token.isEmpty())
            throw new IOException("Mullvad did not return an access token");
        return token;
    }

    private JSONObject createDevice(String token, String publicKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pubkey", publicKey);
        body.put("hijack_dns", false);
        return postJson(API + "/accounts/v1/devices", token, body);
    }

    private List<JSONObject> listDevices(String token) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(API + "/accounts/v1/devices");
        if (!TextUtils.isEmpty(token))
            builder.header("Authorization", "Bearer " + token);

        try (Response response = client.newCall(builder.build()).execute()) {
            String text = responseText(response);
            if (!response.isSuccessful())
                throw new IOException("Mullvad devices request failed: " + response.code() + " " + text);
            JSONArray array = new JSONArray(text);
            List<JSONObject> devices = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null)
                    devices.add(item);
            }
            return devices;
        }
    }

    private List<Relay> fetchRelays() throws Exception {
        Request request = new Request.Builder()
                .url(API + "/www/relays/all")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String text = responseText(response);
            if (!response.isSuccessful())
                throw new IOException("Mullvad relay request failed: " + response.code() + " " + text);

            JSONArray array = new JSONArray(text);
            List<Relay> relays = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null)
                    continue;
                if (!"wireguard".equals(item.optString("type")))
                    continue;
                if (!item.optBoolean("active", false))
                    continue;

                Relay relay = new Relay();
                relay.hostname = item.optString("hostname");
                relay.countryCode = item.optString("country_code");
                relay.countryName = item.optString("country_name");
                relay.provider = item.optString("provider");
                relay.ipv4 = item.optString("ipv4_addr_in");
                relay.publicKey = item.optString("pubkey");
                relay.speed = Math.max(1, item.optInt("network_port_speed", 1));
                relay.stboot = item.optBoolean("stboot", false);
                if (!TextUtils.isEmpty(relay.hostname) &&
                        !TextUtils.isEmpty(relay.ipv4) &&
                        !TextUtils.isEmpty(relay.publicKey))
                    relays.add(relay);
            }
            return relays;
        }
    }

    private Relay chooseRelay(List<Relay> relays, String requestedCountryCode) {
        if (relays.isEmpty())
            throw new IllegalStateException("No active Mullvad WireGuard relays found");

        List<Relay> candidates = filterCountry(relays, normalizeCountry(requestedCountryCode));
        if (candidates.isEmpty())
            candidates = filterCountry(relays, defaultCountry());
        if (candidates.isEmpty())
            candidates = new ArrayList<>(relays);

        List<Relay> stboot = new ArrayList<>();
        for (Relay relay : candidates)
            if (relay.stboot)
                stboot.add(relay);
        if (!stboot.isEmpty())
            candidates = stboot;

        int total = 0;
        for (Relay relay : candidates)
            total += relay.speed;
        int pick = random.nextInt(Math.max(1, total));
        for (Relay relay : candidates) {
            pick -= relay.speed;
            if (pick < 0)
                return relay;
        }
        return candidates.get(0);
    }

    private List<Relay> filterCountry(List<Relay> relays, String countryCode) {
        List<Relay> result = new ArrayList<>();
        if (TextUtils.isEmpty(countryCode))
            return result;
        for (Relay relay : relays)
            if (countryCode.equals(relay.countryCode))
                result.add(relay);
        return result;
    }

    private String buildConfig(String privateKey, JSONObject device, Relay relay) {
        String ipv4 = device.optString("ipv4_address");
        String ipv6 = device.optString("ipv6_address");
        String deviceName = device.optString("name");

        StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n");
        if (!TextUtils.isEmpty(deviceName))
            sb.append("# Mullvad device = ").append(deviceName).append('\n');
        sb.append("PrivateKey = ").append(privateKey).append('\n');
        sb.append("Address = ").append(ipv4);
        if (!TextUtils.isEmpty(ipv6))
            sb.append(", ").append(ipv6);
        sb.append('\n');
        sb.append("DNS = ").append(DEFAULT_DNS).append("\n\n");
        sb.append("[Peer]\n");
        sb.append("# Mullvad relay = ").append(relay.hostname).append('\n');
        if (!TextUtils.isEmpty(relay.provider))
            sb.append("# Mullvad provider = ").append(relay.provider).append('\n');
        sb.append("PublicKey = ").append(relay.publicKey).append('\n');
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n");
        sb.append("Endpoint = ").append(relay.ipv4).append(':').append(DEFAULT_PORT).append('\n');
        return sb.toString();
    }

    private JSONObject postJson(String url, String token, JSONObject body) throws Exception {
        return requestJson("POST", url, token, body);
    }

    private JSONObject requestJson(String method, String url, String token, JSONObject body) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(url);
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        if ("PUT".equals(method))
            builder.put(requestBody);
        else
            builder.post(requestBody);
        if (!TextUtils.isEmpty(token))
            builder.header("Authorization", "Bearer " + token);

        try (Response response = client.newCall(builder.build()).execute()) {
            String text = responseText(response);
            if (!response.isSuccessful())
                throw new ApiRejectedException("Mullvad request failed: " + response.code() +
                        " " + text);
            return new JSONObject(text);
        }
    }

    private String responseText(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private String defaultCountry() {
        return normalizeCountry(Locale.getDefault().getCountry());
    }

    private static String normalizeCountry(String countryCode) {
        if (countryCode == null)
            return "";
        return countryCode.trim().toLowerCase(Locale.ROOT);
    }
}
