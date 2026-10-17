package com.example.slidebox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public class AccountStore {

    private static final Object STATE_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();

    // Prevent an old request from restoring a logged-out session.
    private static long sessionVersion = 0;

    private final SharedPreferences session;

    public static final class SessionExpiredException
            extends IOException {

        public SessionExpiredException() {
            super("Session expired. Please log in again.");
        }
    }

    public AccountStore(Context context) {
        session = context.getApplicationContext()
                .getSharedPreferences(
                        "supabase_session",
                        Context.MODE_PRIVATE
                );
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidRole(String role) {
        return "Teacher".equals(role) || "Student".equals(role);
    }

    private void requireBackgroundThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "Run network operations on a background thread."
            );
        }
    }

    public void register(
            String name,
            String email,
            String password,
            String role
    ) throws Exception {

        requireBackgroundThread();

        name = name.trim();
        email = normalize(email);

        if (name.isEmpty()) {
            throw new IOException("Enter your name.");
        }

        if (!isValidRole(role)) {
            throw new IOException("Select Teacher or Student.");
        }

        JSONObject metadata = new JSONObject();
        metadata.put("name", name);
        metadata.put("role", role);

        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        body.put("data", metadata);

        JSONObject response = new JSONObject(
                SupabaseApi.request(
                        "POST",
                        "/auth/v1/signup",
                        body,
                        null
                )
        );

        String token = response.optString("access_token", "");

        if (token.isEmpty()) {
            throw new IOException(
                    "No login token received. If a confirmation email "
                            + "arrived, confirm it and log in. Otherwise "
                            + "check Confirm email in Supabase."
            );
        }

        JSONObject user = response.getJSONObject("user");

        try {
            ensureProfile(user, token);
        } catch (Exception e) {
            throw new IOException(
                    "Account created, but profile setup failed. "
                            + "Please try Login. If it still fails, "
                            + "check the profiles table and policies.",
                    e
            );
        }

        // Registration returns to the Login screen.
    }

    public boolean login(
            String email,
            String password
    ) throws Exception {

        requireBackgroundThread();

        final long attemptVersion;

        synchronized (STATE_LOCK) {
            attemptVersion = ++sessionVersion;
        }

        JSONObject body = new JSONObject();
        body.put("email", normalize(email));
        body.put("password", password);

        JSONObject response = new JSONObject(
                SupabaseApi.request(
                        "POST",
                        "/auth/v1/token?grant_type=password",
                        body,
                        null
                )
        );

        String token = response.getString("access_token");
        String refresh = response.getString("refresh_token");
        JSONObject user = response.getJSONObject("user");
        JSONObject profile = ensureProfile(user, token);

        if (token.isEmpty() || refresh.isEmpty()) {
            throw new IOException("Login response is incomplete.");
        }

        synchronized (STATE_LOCK) {
            if (attemptVersion != sessionVersion) {
                throw new IOException(
                        "Login was cancelled. Please try again."
                );
            }

            boolean saved = session.edit()
                    .clear()
                    .putString("user_id", user.getString("id"))
                    .putString(
                            "email",
                            user.optString("email", normalize(email))
                    )
                    .putString("name", profile.getString("name"))
                    .putString("role", profile.getString("role"))
                    .putString("access_token", token)
                    .putString("refresh_token", refresh)
                    .putLong("expires_at", expiryTime(response))
                    .commit();

            if (!saved) {
                session.edit().clear().apply();
                throw new IOException("Could not save login session.");
            }
        }

        return true;
    }

    // Call before an online PDF/database operation.
    public String getAccessToken() throws Exception {
        return obtainAccessToken(null);
    }

    // Use after a request rejects a token.
    // If another request already refreshed it, reuse the newer token.
    public String refreshAccessToken(String rejectedToken)
            throws Exception {

        if (rejectedToken == null || rejectedToken.isEmpty()) {
            return getAccessToken();
        }

        return obtainAccessToken(rejectedToken);
    }

    private String obtainAccessToken(String rejectedToken)
            throws Exception {

        requireBackgroundThread();

        // Only one token refresh runs at a time.
        synchronized (REFRESH_LOCK) {
            final String oldRefresh;
            final String userId;
            final long version;

            synchronized (STATE_LOCK) {
                userId = session.getString("user_id", "");
                oldRefresh = session.getString("refresh_token", "");

                if (userId.isEmpty() || oldRefresh.isEmpty()) {
                    throw new SessionExpiredException();
                }

                String token = session.getString("access_token", "");
                long expiresAt = session.getLong("expires_at", 0L);
                long now = System.currentTimeMillis() / 1000L;

                boolean rejected = rejectedToken != null
                        && rejectedToken.equals(token);

                if (!rejected
                        && !token.isEmpty()
                        && expiresAt > now + 60L) {
                    return token;
                }

                version = sessionVersion;
            }

            JSONObject body = new JSONObject();
            body.put("refresh_token", oldRefresh);

            final JSONObject response;

            try {
                response = new JSONObject(
                        SupabaseApi.request(
                                "POST",
                                "/auth/v1/token?grant_type=refresh_token",
                                body,
                                null
                        )
                );
            } catch (SupabaseApi.ApiException e) {
                if (e.statusCode == 400
                        || e.statusCode == 401
                        || e.statusCode == 403
                        || e.statusCode == 422) {

                    synchronized (STATE_LOCK) {
                        if (version == sessionVersion
                                && oldRefresh.equals(
                                session.getString(
                                        "refresh_token", ""
                                )
                        )) {
                            sessionVersion++;
                            session.edit().clear().apply();
                        }
                    }

                    throw new SessionExpiredException();
                }

                // Server/rate-limit failures keep the saved session.
                throw e;
            }

            // Network failures also keep the saved session.
            String newToken = response.getString("access_token");
            String newRefresh = response.getString("refresh_token");

            JSONObject user = response.getJSONObject("user");

            if (newToken.isEmpty()
                    || newRefresh.isEmpty()
                    || !userId.equals(user.getString("id"))) {
                throw new IOException("Invalid session refresh response.");
            }

            synchronized (STATE_LOCK) {
                // Logout or another login may have happened meanwhile.
                if (version != sessionVersion
                        || !oldRefresh.equals(
                        session.getString("refresh_token", "")
                )) {
                    throw new SessionExpiredException();
                }

                boolean saved = session.edit()
                        .putString("access_token", newToken)
                        .putString("refresh_token", newRefresh)
                        .putLong("expires_at", expiryTime(response))
                        .commit();

                if (!saved) {
                    sessionVersion++;
                    session.edit().clear().apply();
                    throw new SessionExpiredException();
                }
            }

            return newToken;
        }
    }

    private long expiryTime(JSONObject response) {
        long absoluteExpiry = response.optLong("expires_at", 0L);

        if (absoluteExpiry > 0L) {
            return absoluteExpiry;
        }

        return System.currentTimeMillis() / 1000L
                + response.optLong("expires_in", 3600L);
    }

    private JSONObject readProfile(
            String userId,
            String token
    ) throws Exception {

        UUID.fromString(userId);

        String path = "/rest/v1/profiles"
                + "?id=eq." + userId
                + "&select=id,name,role"
                + "&limit=1";

        JSONArray rows = new JSONArray(
                SupabaseApi.request(
                        "GET",
                        path,
                        null,
                        token
                )
        );

        return rows.length() == 0
                ? null
                : rows.getJSONObject(0);
    }

    private JSONObject ensureProfile(
            JSONObject user,
            String token
    ) throws Exception {

        String userId = user.getString("id");
        JSONObject profile = readProfile(userId, token);

        if (profile == null) {
            JSONObject metadata = user.optJSONObject("user_metadata");

            if (metadata == null) {
                throw new IOException(
                        "Account profile information is missing."
                );
            }

            String name = metadata.optString("name", "").trim();
            String role = metadata.optString("role", "");

            if (name.isEmpty() || !isValidRole(role)) {
                throw new IOException(
                        "Account name or role is missing."
                );
            }

            JSONObject body = new JSONObject();
            body.put("id", userId);
            body.put("name", name);
            body.put("role", role);

            try {
                SupabaseApi.request(
                        "POST",
                        "/rest/v1/profiles",
                        body,
                        token
                );
            } catch (SupabaseApi.ApiException e) {
                if (e.statusCode != 409) {
                    throw e;
                }
            }

            profile = readProfile(userId, token);
        }

        if (profile == null) {
            throw new IOException(
                    "Could not read profile. Check profiles policies."
            );
        }

        if (profile.optString("name", "").trim().isEmpty()
                || !isValidRole(profile.optString("role", ""))) {
            throw new IOException("Invalid account profile.");
        }

        return profile;
    }

    public boolean isLoggedIn() {
        synchronized (STATE_LOCK) {
            // Checks saved session presence, not server validity.
            return !session.getString("user_id", "").isEmpty()
                    && !session.getString(
                    "refresh_token", ""
            ).isEmpty();
        }
    }

    public String getName() {
        return session.getString("name", "");
    }

    public String getRole() {
        return session.getString("role", "");
    }

    public String getEmail() {
        return session.getString("email", "");
    }

    public String getUserId() {
        return session.getString("user_id", "");
    }

    public void logout() {
        synchronized (STATE_LOCK) {
            sessionVersion++;

            // Clears this phone's session immediately.
            session.edit().clear().apply();
        }
    }

    public static String registrationError(Exception error) {
        if (error instanceof SupabaseApi.ApiException) {
            int status = ((SupabaseApi.ApiException) error).statusCode;

            if (status == 429) {
                return "Too many attempts. Please wait and try again.";
            }

            if (status == 400 || status == 422) {
                return "Registration rejected. The email may already "
                        + "be registered, or the email/password may "
                        + "not meet Supabase requirements. "
                        + "If registered, try Login.";
            }

            if (status == 401 || status == 403) {
                return "Request denied. Check Supabase configuration "
                        + "and signup settings.";
            }

            return "Supabase error (" + status + "). Please try again.";
        }

        if (error instanceof java.net.UnknownHostException
                || error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.ConnectException) {
            return "Cannot connect. Check your internet and try again.";
        }

        if (error instanceof IOException) {
            String message = error.getMessage();

            if (message != null && !message.isEmpty()) {
                return message;
            }
        }

        return "Could not create account. Please try again.";
    }
}