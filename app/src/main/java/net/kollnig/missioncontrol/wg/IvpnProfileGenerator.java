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

public class IvpnProfileGenerator {
    private static final String API = "https://api.ivpn.net";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_DNS = "172.16.0.1";
    private static final int DEFAULT_PORT = 2049;

    private final OkHttpClient client = new OkHttpClient();
    private final SecureRandom random = new SecureRandom();

    public static class CountryOption {
        public final String code;
        public final String name;

        public CountryOption(String code, String name) {
            this.code = normalizeCountry(code);
            this.name = name == null ? "" : name;
        }
    }

    public static class GeneratedProfile {
        public final String name;
        public final String config;
        public final String accountNumber;
        public final String countryCode;
        public final String countryName;
        public final String relayHostname;
        public final WgProfileManager.IvpnSession session;

        public GeneratedProfile(String name, String config, String accountNumber,
                                String countryCode, String countryName, String relayHostname,
                                WgProfileManager.IvpnSession session) {
            this.name = name;
            this.config = config;
            this.accountNumber = accountNumber;
            this.countryCode = normalizeCountry(countryCode);
            this.countryName = countryName == null ? "" : countryName;
            this.relayHostname = relayHostname == null ? "" : relayHostname;
            this.session = session;
        }
    }

    public static class CaptchaRequiredException extends Exception {
        public final String captchaId;
        public final String captchaImage;

        public CaptchaRequiredException(String captchaId, String captchaImage, String message) {
            super(TextUtils.isEmpty(message) ? "IVPN requires CAPTCHA verification" : message);
            this.captchaId = captchaId == null ? "" : captchaId;
            this.captchaImage = captchaImage == null ? "" : captchaImage;
        }
    }

    public static class ApiRejectedException extends IOException {
        ApiRejectedException(String message) {
            super(message);
        }
    }

    private static class Relay {
        String hostname;
        String countryCode;
        String countryName;
        String city;
        String host;
        String publicKey;
        String localIp;
        double load;
    }

    public List<CountryOption> fetchCountryOptions() throws Exception {
        Map<String, String> countries = new LinkedHashMap<>();
        for (Relay relay : fetchRelays())
            countries.put(relay.countryCode, relay.countryName);

        List<CountryOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : countries.entrySet())
            options.add(new CountryOption(entry.getKey(), entry.getValue()));
        Collections.sort(options, Comparator.comparing(o -> o.name));
        return options;
    }

    public GeneratedProfile generate(String accountNumber, String requestedCountryCode,
                                     WgProfileManager.IvpnSession reusableSession)
            throws Exception {
        return generate(accountNumber, requestedCountryCode, reusableSession, "", "");
    }

    public GeneratedProfile generate(String accountNumber, String requestedCountryCode,
                                     WgProfileManager.IvpnSession reusableSession,
                                     String captchaId, String captchaValue)
            throws Exception {
        String account = accountNumber == null ? "" : accountNumber.trim();
        if (account.isEmpty())
            throw new IllegalArgumentException("IVPN account number is required");

        WgProfileManager.IvpnSession session = reusableSession;
        if (session == null || !session.isUsable()) {
            String privateKey = Wgbridge.generatePrivateKey();
            String publicKey = Wgbridge.publicKey(privateKey);
            session = createSession(account, privateKey, publicKey, captchaId, captchaValue);
        }

        Relay relay = chooseRelay(fetchRelays(), requestedCountryCode);
        String config = buildConfig(session.privateKey, session.address, relay);
        return new GeneratedProfile("IVPN - " + relay.countryName, config, account,
                relay.countryCode, relay.countryName, relay.hostname, session);
    }

    public WgProfileManager.IvpnSession rotateSessionKey(WgProfileManager.IvpnSession session,
                                                         String newPrivateKey,
                                                         String newPublicKey,
                                                         String connectedPublicKey)
            throws Exception {
        if (session == null || !session.isUsable())
            throw new IllegalArgumentException("IVPN session is required");
        if (TextUtils.isEmpty(newPrivateKey) || TextUtils.isEmpty(newPublicKey))
            throw new IllegalArgumentException("IVPN key material is required");

        JSONObject body = new JSONObject();
        body.put("session_token", session.token);
        body.put("public_key", newPublicKey);
        body.put("connected_public_key", connectedPublicKey == null ? "" : connectedPublicKey);

        JSONObject response = postJson(API + "/v4/session/wg/set", body);
        int status = response.optInt("status", 0);
        if (status != 200)
            throw new ApiRejectedException(errorMessage(response,
                    "IVPN WireGuard key update failed"));
        String address = response.optString("ip_address", "");
        if (TextUtils.isEmpty(address))
            throw new IOException("IVPN did not return a WireGuard address");
        return new WgProfileManager.IvpnSession(session.token, newPrivateKey, newPublicKey, address);
    }

    private WgProfileManager.IvpnSession createSession(String account, String privateKey,
                                                       String publicKey, String captchaId,
                                                       String captchaValue)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", account);
        body.put("wg_public_key", publicKey);
        body.put("force", false);
        body.put("app_name", "TrackerControl");
        if (!TextUtils.isEmpty(captchaId) && !TextUtils.isEmpty(captchaValue)) {
            body.put("captcha_id", captchaId);
            body.put("captcha", captchaValue);
        }

        JSONObject response = postJson(API + "/v4/session/new", body);
        int status = response.optInt("status", 0);
        String captcha = response.optString("captcha_image", "");
        String nextCaptchaId = response.optString("captcha_id", "");
        if (status == 70001 || !TextUtils.isEmpty(captcha) || !TextUtils.isEmpty(nextCaptchaId))
            throw new CaptchaRequiredException(nextCaptchaId, captcha,
                    response.optString("message", ""));
        if (status != 200)
            throw new IOException(errorMessage(response, "IVPN session request failed"));

        JSONObject wireGuard = response.optJSONObject("wireguard");
        if (wireGuard == null)
            throw new IOException("IVPN did not return WireGuard session data");
        int wgStatus = wireGuard.optInt("status", 0);
        if (wgStatus != 200)
            throw new IOException(errorMessage(wireGuard, "IVPN WireGuard setup failed"));

        String token = response.optString("token", "");
        String address = wireGuard.optString("ip_address", "");
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(address))
            throw new IOException("IVPN session response was incomplete");
        return new WgProfileManager.IvpnSession(token, privateKey, publicKey, address);
    }

    private List<Relay> fetchRelays() throws Exception {
        Request request = new Request.Builder()
                .url(API + "/v5/servers.json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String text = responseText(response);
            if (!response.isSuccessful())
                throw new IOException("IVPN server request failed: " + response.code() + " " + text);

            JSONObject root = new JSONObject(text);
            JSONArray servers = root.optJSONArray("wireguard");
            if (servers == null)
                throw new IOException("IVPN did not return WireGuard servers");

            List<Relay> relays = new ArrayList<>();
            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.optJSONObject(i);
                if (server == null)
                    continue;
                JSONArray hosts = server.optJSONArray("hosts");
                if (hosts == null)
                    continue;
                for (int j = 0; j < hosts.length(); j++) {
                    JSONObject host = hosts.optJSONObject(j);
                    if (host == null)
                        continue;
                    Relay relay = new Relay();
                    relay.hostname = host.optString("hostname");
                    relay.countryCode = normalizeCountry(server.optString("country_code"));
                    relay.countryName = server.optString("country");
                    relay.city = server.optString("city");
                    relay.host = host.optString("host");
                    relay.publicKey = host.optString("public_key");
                    relay.localIp = host.optString("local_ip");
                    relay.load = Math.max(0d, host.optDouble("load", 0d));
                    if (!TextUtils.isEmpty(relay.hostname) &&
                            !TextUtils.isEmpty(relay.countryCode) &&
                            !TextUtils.isEmpty(relay.countryName) &&
                            !TextUtils.isEmpty(relay.host) &&
                            !TextUtils.isEmpty(relay.publicKey))
                        relays.add(relay);
                }
            }
            return relays;
        }
    }

    private Relay chooseRelay(List<Relay> relays, String requestedCountryCode) {
        if (relays.isEmpty())
            throw new IllegalStateException("No IVPN WireGuard relays found");

        List<Relay> candidates = filterCountry(relays, normalizeCountry(requestedCountryCode));
        if (candidates.isEmpty())
            candidates = filterCountry(relays, defaultCountry());
        if (candidates.isEmpty())
            candidates = new ArrayList<>(relays);

        Collections.sort(candidates, Comparator.comparingDouble(relay -> relay.load));
        int bestPool = Math.min(3, candidates.size());
        return candidates.get(random.nextInt(Math.max(1, bestPool)));
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

    private String buildConfig(String privateKey, String address, Relay relay) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n");
        sb.append("PrivateKey = ").append(privateKey).append('\n');
        sb.append("Address = ").append(addressWithCidr(address)).append('\n');
        sb.append("DNS = ").append(dnsFromRelay(relay)).append("\n\n");
        sb.append("[Peer]\n");
        sb.append("# IVPN relay = ").append(relay.hostname).append('\n');
        if (!TextUtils.isEmpty(relay.city))
            sb.append("# IVPN city = ").append(relay.city).append('\n');
        sb.append("PublicKey = ").append(relay.publicKey).append('\n');
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n");
        sb.append("Endpoint = ").append(relay.host).append(':').append(DEFAULT_PORT).append('\n');
        sb.append("PersistentKeepalive = 25\n");
        return sb.toString();
    }

    private String addressWithCidr(String address) {
        String trimmed = address == null ? "" : address.trim();
        if (trimmed.contains("/"))
            return trimmed;
        return trimmed + "/32";
    }

    private String dnsFromRelay(Relay relay) {
        if (TextUtils.isEmpty(relay.localIp))
            return DEFAULT_DNS;
        String[] parts = relay.localIp.split("/");
        return TextUtils.isEmpty(parts[0]) ? DEFAULT_DNS : parts[0];
    }

    private JSONObject postJson(String url, JSONObject body) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String text = responseText(response);
            JSONObject json = TextUtils.isEmpty(text) ? new JSONObject() : new JSONObject(text);
            if (!response.isSuccessful() && json.optInt("status", 0) != 70001)
                throw new ApiRejectedException("IVPN request failed: " + response.code() + " " +
                        errorMessage(json, text));
            return json;
        }
    }

    private String responseText(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private String errorMessage(JSONObject response, String fallback) {
        String message = response.optString("message", "");
        if (!TextUtils.isEmpty(message))
            return message;
        int status = response.optInt("status", 0);
        if (status == 602)
            return "IVPN session limit reached";
        if (status == 702)
            return "IVPN account is not active";
        if (status == 70011)
            return "IVPN two-factor confirmation is required";
        if (status == 70012)
            return "IVPN two-factor confirmation was invalid";
        if (status == 70002)
            return "IVPN CAPTCHA was invalid";
        return fallback;
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
