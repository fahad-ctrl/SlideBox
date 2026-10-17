package com.example.slidebox;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AccountStore {

    private final SharedPreferences accounts;
    private final SharedPreferences session;

    public AccountStore(Context context) {
        accounts = context.getSharedPreferences(
                "accounts", Context.MODE_PRIVATE
        );
        session = context.getSharedPreferences(
                "session", Context.MODE_PRIVATE
        );
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String key(String email) {
        return Base64.encodeToString(
                normalize(email).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                ),
                Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    private byte[] hash(String password, byte[] salt)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(), salt, 120000, 256
        );

        try {
            return SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public boolean exists(String email) {
        return accounts.contains(key(email) + "_hash");
    }

    public void register(
            String name,
            String email,
            String password,
            String role
    ) throws Exception {
        if (exists(email)) {
            throw new IllegalArgumentException(
                    "This email is already registered"
            );
        }

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        String prefix = key(email);

        boolean saved = accounts.edit()
                .putString(prefix + "_name", name)
                .putString(prefix + "_role", role)
                .putString(
                        prefix + "_salt",
                        Base64.encodeToString(salt, Base64.NO_WRAP)
                )
                .putString(
                        prefix + "_hash",
                        Base64.encodeToString(
                                hash(password, salt), Base64.NO_WRAP
                        )
                )
                .commit();

        if (!saved) {
            throw new java.io.IOException("Could not save account");
        }
    }

    public boolean login(String email, String password)
            throws Exception {
        String prefix = key(email);
        String storedHash = accounts.getString(
                prefix + "_hash", null
        );
        String storedSalt = accounts.getString(
                prefix + "_salt", null
        );

        if (storedHash == null || storedSalt == null) {
            return false;
        }

        byte[] expected = Base64.decode(
                storedHash, Base64.NO_WRAP
        );
        byte[] actual = hash(
                password,
                Base64.decode(storedSalt, Base64.NO_WRAP)
        );

        if (!MessageDigest.isEqual(expected, actual)) {
            return false;
        }

        if (!session.edit()
                .putString("email", normalize(email))
                .commit()) {
            throw new java.io.IOException("Could not save session");
        }

        return true;
    }

    public boolean isLoggedIn() {
        String email = session.getString("email", null);
        return email != null && exists(email);
    }

    public String getName() {
        String email = session.getString("email", "");
        return accounts.getString(key(email) + "_name", "");
    }

    public String getRole() {
        String email = session.getString("email", "");
        return accounts.getString(
                key(email) + "_role", "Student"
        );
    }

    public void logout() {
        session.edit().clear().apply();
    }
}