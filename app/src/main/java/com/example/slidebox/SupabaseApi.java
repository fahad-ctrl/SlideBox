package com.example.slidebox;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class SupabaseApi {

    // Conservative 20 MB limit.
    public static final long MAX_PDF_BYTES = 20_000_000L;

    private static final int MAX_TEXT_BYTES = 4_000_000;

    private SupabaseApi() {
    }

    public static final class ApiException extends IOException {

        public final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // All network methods must run on a background thread.

    // Existing Login/Register calls continue to use this method.
    public static String request(
            String method,
            String path,
            JSONObject body,
            String accessToken
    ) throws IOException {

        return request(
                method,
                path,
                body,
                accessToken,
                null
        );
    }

    // Extra "prefer" argument can request inserted database rows.
    public static String request(
            String method,
            String path,
            JSONObject body,
            String accessToken,
            String prefer
    ) throws IOException {

        HttpURLConnection connection = openConnection(
                method,
                path,
                accessToken
        );

        try {
            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            if (prefer != null && !prefer.isEmpty()) {
                connection.setRequestProperty("Prefer", prefer);
            }

            if (body != null) {
                byte[] data = body.toString().getBytes(
                        StandardCharsets.UTF_8
                );

                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );
                connection.setFixedLengthStreamingMode(data.length);

                try (OutputStream output =
                             connection.getOutputStream()) {
                    output.write(data);
                }
            }

            return readResponse(connection);

        } finally {
            connection.disconnect();
        }
    }

    // storagePath example: USER_UUID/random-uuid.pdf
    // The caller supplies a temporary local copy of the PDF.
    public static String uploadPdf(
            String storagePath,
            File pdfFile,
            String accessToken
    ) throws IOException {

        requireToken(accessToken);

        if (pdfFile == null || !pdfFile.isFile()) {
            throw new IOException("Selected PDF was not found.");
        }

        long size = pdfFile.length();

        if (size <= 0) {
            throw new IOException("The PDF is empty.");
        }

        if (size > MAX_PDF_BYTES) {
            throw new IOException("Select a PDF smaller than 20 MB.");
        }

        String path = "/storage/v1/object/"
                + encodedBucket()
                + "/"
                + encodeStoragePath(storagePath);

        HttpURLConnection connection = openConnection(
                "POST",
                path,
                accessToken
        );

        try {
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type",
                    "application/pdf"
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            // Never overwrite an existing object.
            connection.setRequestProperty("x-upsert", "false");
            connection.setFixedLengthStreamingMode(size);

            try (
                    InputStream input = new FileInputStream(pdfFile);
                    OutputStream output = connection.getOutputStream()
            ) {
                long copied = copyLimited(
                        input,
                        output,
                        MAX_PDF_BYTES
                );

                if (copied != size) {
                    throw new IOException(
                            "The PDF changed during upload. Try again."
                    );
                }

                output.flush();
            }

            return readResponse(connection);

        } finally {
            connection.disconnect();
        }
    }

    // Downloads a private PDF using the logged-in user's token.
    // Returns a new local PDF file; removes partial files on failure.
    public static File downloadPdf(
            String storagePath,
            File directory,
            String accessToken
    ) throws IOException {

        requireToken(accessToken);

        if (directory == null) {
            throw new IOException("Download folder is missing.");
        }

        if (!directory.isDirectory()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("Could not create download folder.");
        }

        String path = "/storage/v1/object/authenticated/"
                + encodedBucket()
                + "/"
                + encodeStoragePath(storagePath);

        HttpURLConnection connection = openConnection(
                "GET",
                path,
                accessToken
        );

        File downloadedFile = null;
        boolean completed = false;

        try {
            connection.setReadTimeout(60000);
            connection.setRequestProperty(
                    "Accept",
                    "application/pdf"
            );

            int status = connection.getResponseCode();

            if (status < 200 || status >= 300) {
                String response = readText(
                        connection.getErrorStream()
                );

                throw new ApiException(
                        status,
                        getErrorMessage(response, status)
                );
            }

            long expectedSize = -1L;
            String lengthHeader = connection.getHeaderField(
                    "Content-Length"
            );

            if (lengthHeader != null) {
                try {
                    expectedSize = Long.parseLong(lengthHeader);
                } catch (NumberFormatException ignored) {
                }
            }

            if (expectedSize > MAX_PDF_BYTES) {
                throw new IOException("This PDF exceeds the 20 MB limit.");
            }

            downloadedFile = File.createTempFile(
                    "slide-",
                    ".pdf",
                    directory
            );

            long copied;

            try (
                    InputStream input = connection.getInputStream();
                    OutputStream output =
                            new FileOutputStream(downloadedFile)
            ) {
                copied = copyLimited(
                        input,
                        output,
                        MAX_PDF_BYTES
                );

                output.flush();
            }

            if (copied == 0) {
                throw new IOException("Downloaded PDF is empty.");
            }

            // Content-Length refers to encoded bytes if compression
            // is present, so compare only uncompressed responses.
            String encoding = connection.getHeaderField(
                    "Content-Encoding"
            );

            if (expectedSize >= 0
                    && (encoding == null
                    || "identity".equalsIgnoreCase(encoding))
                    && copied != expectedSize) {
                throw new IOException(
                        "Download was incomplete. Please try again."
                );
            }

            completed = true;
            return downloadedFile;

        } finally {
            connection.disconnect();

            if (!completed && downloadedFile != null) {
                downloadedFile.delete();
            }
        }
    }

    // Returns the server response listing removed objects.
    // The caller must inspect it before reporting deletion success.
    public static String deletePdf(
            String storagePath,
            String accessToken
    ) throws IOException {

        requireToken(accessToken);

        // Validate before placing the path in the JSON body.
        encodeStoragePath(storagePath);

        JSONObject body = new JSONObject();
        JSONArray paths = new JSONArray();
        paths.put(storagePath);

        try {
            body.put("prefixes", paths);
        } catch (JSONException e) {
            throw new IOException("Could not prepare delete request.", e);
        }

        return request(
                "DELETE",
                "/storage/v1/object/" + encodedBucket(),
                body,
                accessToken
        );
    }

    private static HttpURLConnection openConnection(
            String method,
            String path,
            String accessToken
    ) throws IOException {

        if (path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.contains("\\")
                || path.contains("#")
                || path.contains("\r")
                || path.contains("\n")) {
            throw new IOException("Invalid API path.");
        }

        URL url = new URL(SupabaseConfig.URL + path);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);

        connection.setRequestProperty(
                "apikey",
                SupabaseConfig.PUBLISHABLE_KEY
        );

        if (accessToken != null && !accessToken.isEmpty()) {
            connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + accessToken
            );
        }

        return connection;
    }

    private static String encodedBucket() throws IOException {
        String bucket = SupabaseConfig.BUCKET;

        if (bucket == null || bucket.isEmpty()
                || bucket.contains("/")) {
            throw new IOException("Invalid storage bucket.");
        }

        return Uri.encode(bucket);
    }

    private static String encodeStoragePath(String path)
            throws IOException {

        if (path == null || path.isEmpty()) {
            throw new IOException("PDF storage path is missing.");
        }

        String[] parts = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()
                    || ".".equals(part)
                    || "..".equals(part)
                    || part.contains("\\")
                    || part.contains("\r")
                    || part.contains("\n")) {
                throw new IOException("Invalid PDF storage path.");
            }

            if (encoded.length() > 0) {
                encoded.append("/");
            }

            encoded.append(Uri.encode(part));
        }

        return encoded.toString();
    }

    private static void requireToken(String accessToken)
            throws IOException {

        if (accessToken == null || accessToken.isEmpty()) {
            throw new IOException("Please log in again.");
        }
    }

    private static long copyLimited(
            InputStream input,
            OutputStream output,
            long limit
    ) throws IOException {

        byte[] buffer = new byte[8192];
        long total = 0;
        int count;

        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Transfer cancelled.");
            }

            total += count;

            if (total > limit) {
                throw new IOException("The PDF exceeds the 20 MB limit.");
            }

            output.write(buffer, 0, count);
        }

        return total;
    }

    private static String readResponse(
            HttpURLConnection connection
    ) throws IOException {

        int status = connection.getResponseCode();
        boolean success = status >= 200 && status < 300;

        String response = readText(
                success
                        ? connection.getInputStream()
                        : connection.getErrorStream()
        );

        if (!success) {
            throw new ApiException(
                    status,
                    getErrorMessage(response, status)
            );
        }

        return response;
    }

    private static String readText(InputStream stream)
            throws IOException {

        if (stream == null) {
            return "";
        }

        try (
                InputStream input = stream;
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;

            while ((count = input.read(buffer)) != -1) {
                total += count;

                if (total > MAX_TEXT_BYTES) {
                    throw new IOException("Server response is too large.");
                }

                output.write(buffer, 0, count);
            }

            return new String(
                    output.toByteArray(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private static String getErrorMessage(
            String response,
            int status
    ) {
        try {
            JSONObject error = new JSONObject(response);

            String[] fields = {
                    "msg",
                    "message",
                    "error_description",
                    "error"
            };

            for (String field : fields) {
                String message = error.optString(field, "");

                if (!message.isEmpty()
                        && !"null".equals(message)) {
                    return message;
                }
            }

        } catch (Exception ignored) {
        }

        return "Request failed (HTTP " + status + ")";
    }
}