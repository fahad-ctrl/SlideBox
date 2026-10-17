package com.example.slidebox;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private AccountStore accounts;

    private Button btnLogin;
    private Button btnRegister;
    private Button btnGuest;

    private boolean busy;
    private File pendingPdf;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> saveLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/pdf"
                    ),
                    destination -> {
                        File source = pendingPdf;
                        pendingPdf = null;

                        if (destination == null) {
                            if (source != null) {
                                source.delete();
                            }
                            setBusy(false);
                            return;
                        }

                        if (source == null || !source.isFile()) {
                            setBusy(false);
                            toast("PDF is missing. Please download again.");
                            return;
                        }

                        setBusy(true);

                        worker.execute(() -> {
                            String error = null;

                            try (
                                    InputStream input =
                                            new FileInputStream(source);
                                    OutputStream output =
                                            getContentResolver()
                                                    .openOutputStream(
                                                            destination,
                                                            "wt"
                                                    )
                            ) {
                                if (output == null) {
                                    throw new IOException(
                                            "Could not open save location."
                                    );
                                }

                                copyPdf(input, output);

                            } catch (Exception e) {
                                error = messageOf(e);
                            } finally {
                                source.delete();
                            }

                            String result = error;

                            runOnUiThread(() -> {
                                if (!screenActive()) {
                                    return;
                                }

                                setBusy(false);

                                toast(result == null
                                        ? "PDF saved successfully."
                                        : result);
                            });
                        });
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accounts = new AccountStore(this);

        if (accounts.isLoggedIn()) {
            openDashboard();
            return;
        }

        setContentView(R.layout.activity_main);

        LinearLayout main = findViewById(R.id.main);

        int padding = Math.round(
                24 * getResources().getDisplayMetrics().density
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                main,
                (view, insets) -> {
                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );

                    view.setPadding(
                            padding + bars.left,
                            padding + bars.top,
                            padding + bars.right,
                            padding + bars.bottom
                    );

                    return insets;
                }
        );

        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);

        btnGuest = new Button(this);
        btnGuest.setText("Download PDF without Login");
        btnGuest.setAllCaps(false);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = Math.round(
                12 * getResources().getDisplayMetrics().density
        );

        main.addView(btnGuest, params);

        if (savedInstanceState != null) {
            String fileName = savedInstanceState.getString(
                    "pending_pdf"
            );

            if (fileName != null
                    && fileName.equals(new File(fileName).getName())) {
                File restored = new File(pdfDirectory(), fileName);

                if (restored.isFile()) {
                    pendingPdf = restored;
                }
            }
        }

        btnLogin.setOnClickListener(view -> showLoginDialog());

        btnRegister.setOnClickListener(view ->
                startActivity(new Intent(
                        this,
                        RegisterActivity.class
                ))
        );

        btnGuest.setOnClickListener(view -> showGuestDialog());

        setBusy(pendingPdf != null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (pendingPdf != null) {
            outState.putString(
                    "pending_pdf",
                    pendingPdf.getName()
            );
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private ContextThemeWrapper dialogTheme() {
        return new ContextThemeWrapper(
                this,
                androidx.appcompat.R.style
                        .Theme_AppCompat_Light_Dialog_Alert
        );
    }

    private LinearLayout form(ContextThemeWrapper theme) {
        LinearLayout form = new LinearLayout(theme);
        form.setOrientation(LinearLayout.VERTICAL);

        int padding = Math.round(
                24 * getResources().getDisplayMetrics().density
        );

        form.setPadding(padding, padding, padding, padding);
        return form;
    }

    private void showLoginDialog() {
        if (busy) {
            return;
        }

        ContextThemeWrapper theme = dialogTheme();
        LinearLayout form = form(theme);

        EditText emailInput = new EditText(theme);
        emailInput.setHint("Email");
        emailInput.setSingleLine(true);
        emailInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        form.addView(emailInput);

        EditText passwordInput = new EditText(theme);
        passwordInput.setHint("Password");
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        form.addView(passwordInput);

        AlertDialog dialog = new AlertDialog.Builder(theme)
                .setTitle("Login to SlideBox")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Login", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button loginButton = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE
            );

            loginButton.setOnClickListener(view -> {
                String email = emailInput.getText()
                        .toString().trim();

                String password = passwordInput.getText()
                        .toString();

                if (email.isEmpty()) {
                    emailInput.setError("Enter your email");
                    return;
                }

                if (password.isEmpty()) {
                    passwordInput.setError("Enter your password");
                    return;
                }

                setBusy(true);
                loginButton.setEnabled(false);
                loginButton.setText("Checking...");
                emailInput.setEnabled(false);
                passwordInput.setEnabled(false);
                dialog.setCancelable(false);

                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        .setEnabled(false);

                worker.execute(() -> {
                    boolean success = false;
                    String error = null;

                    try {
                        success = accounts.login(email, password);
                    } catch (Exception e) {
                        error = messageOf(e);
                    }

                    boolean loggedIn = success;
                    String message = error;

                    runOnUiThread(() -> {
                        if (!screenActive()) {
                            return;
                        }

                        setBusy(false);
                        loginButton.setEnabled(true);
                        loginButton.setText("Login");
                        emailInput.setEnabled(true);
                        passwordInput.setEnabled(true);
                        dialog.setCancelable(true);

                        dialog.getButton(
                                AlertDialog.BUTTON_NEGATIVE
                        ).setEnabled(true);

                        if (loggedIn) {
                            dialog.dismiss();
                            openDashboard();
                        } else if (message != null) {
                            toast(message);
                        } else {
                            passwordInput.setError(
                                    "Incorrect email or password"
                            );
                        }
                    });
                });
            });
        });

        dialog.show();
    }

    private void showGuestDialog() {
        if (busy) {
            return;
        }

        ContextThemeWrapper theme = dialogTheme();
        LinearLayout form = form(theme);

        EditText codeInput = new EditText(theme);
        codeInput.setHint("Enter slide code");
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(codeInput);

        AlertDialog dialog = new AlertDialog.Builder(theme)
                .setTitle("Get PDF without Login")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Save PDF", null)
                .setPositiveButton("Open PDF", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view ->
                            submitCode(codeInput, dialog, false)
                    );

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view ->
                            submitCode(codeInput, dialog, true)
                    );
        });

        dialog.show();
    }

    private void submitCode(
            EditText input,
            AlertDialog dialog,
            boolean save
    ) {
        String text = input.getText().toString().trim();
        long code;

        try {
            if (!text.matches("[0-9]+")) {
                throw new NumberFormatException();
            }

            code = Long.parseLong(text);

            if (code <= 0) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {
            input.setError("Enter a valid slide code");
            return;
        }

        dialog.dismiss();
        downloadGuestPdf(code, save);
    }

    private void downloadGuestPdf(long code, boolean save) {
        if (busy) {
            return;
        }

        setBusy(true);

        worker.execute(() -> {
            File downloaded = null;

            try {
                JSONObject body = new JSONObject();
                body.put("p_code", code);

                String response = SupabaseApi.request(
                        "POST",
                        "/rest/v1/rpc/get_slide_by_code",
                        body,
                        null
                );

                JSONArray rows = new JSONArray(response);

                if (rows.length() == 0) {
                    throw new IOException(
                            "No PDF found for this code."
                    );
                }

                JSONObject slide = rows.getJSONObject(0);

                String fileName = safeName(
                        slide.getString("file_name")
                );

                downloaded = downloadPublicPdf(
                        slide.getString("storage_path")
                );

                File result = downloaded;

                runOnUiThread(() -> {
                    if (!screenActive()) {
                        result.delete();
                        return;
                    }

                    if (save) {
                        pendingPdf = result;

                        try {
                            saveLauncher.launch(fileName);
                        } catch (Exception e) {
                            pendingPdf = null;
                            result.delete();
                            setBusy(false);
                            toast(messageOf(e));
                        }
                    } else {
                        setBusy(false);
                        openPdf(result);
                    }
                });

            } catch (Exception e) {
                if (downloaded != null) {
                    downloaded.delete();
                }

                String error = messageOf(e);

                runOnUiThread(() -> {
                    if (!screenActive()) {
                        return;
                    }

                    setBusy(false);
                    toast(error);
                });
            }
        });
    }

    private File downloadPublicPdf(String storagePath)
            throws IOException {

        StringBuilder encodedPath = new StringBuilder();

        for (String part : storagePath.split("/", -1)) {
            if (part.isEmpty()
                    || ".".equals(part)
                    || "..".equals(part)
                    || part.contains("\\")) {
                throw new IOException("Invalid PDF path.");
            }

            if (encodedPath.length() > 0) {
                encodedPath.append("/");
            }

            encodedPath.append(Uri.encode(part));
        }

        URL url = new URL(
                SupabaseConfig.URL
                        + "/storage/v1/object/public/"
                        + Uri.encode(SupabaseConfig.BUCKET)
                        + "/"
                        + encodedPath
        );

        File directory = pdfDirectory();

        if (!directory.isDirectory()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException(
                    "Could not create download folder."
            );
        }

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        File result = null;
        boolean completed = false;

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "Accept",
                    "application/pdf"
            );
            connection.setRequestProperty(
                    "Accept-Encoding",
                    "identity"
            );

            int status = connection.getResponseCode();

            if (status < 200 || status >= 300) {
                throw new IOException(
                        "Could not download PDF (HTTP "
                                + status
                                + "). Check the code and bucket settings."
                );
            }

            long expected = -1;
            String length = connection.getHeaderField(
                    "Content-Length"
            );

            if (length != null) {
                try {
                    expected = Long.parseLong(length);
                } catch (NumberFormatException ignored) {
                }
            }

            if (expected > SupabaseApi.MAX_PDF_BYTES) {
                throw new IOException(
                        "This PDF exceeds the 20 MB limit."
                );
            }

            result = File.createTempFile(
                    "guest-",
                    ".pdf",
                    directory
            );

            long copied;

            try (
                    InputStream input = connection.getInputStream();
                    OutputStream output = new FileOutputStream(result)
            ) {
                copied = copyPdf(input, output);
            }

            if (copied == 0) {
                throw new IOException("Downloaded PDF is empty.");
            }

            String encoding = connection.getHeaderField(
                    "Content-Encoding"
            );

            if (expected >= 0
                    && (encoding == null
                    || "identity".equalsIgnoreCase(encoding))
                    && copied != expected) {
                throw new IOException(
                        "Incomplete download. Please try again."
                );
            }

            completed = true;
            return result;

        } finally {
            connection.disconnect();

            if (!completed && result != null) {
                result.delete();
            }
        }
    }

    private long copyPdf(InputStream input, OutputStream output)
            throws IOException {

        byte[] buffer = new byte[8192];
        long total = 0;
        int count;

        while ((count = input.read(buffer)) != -1) {
            total += count;

            if (total > SupabaseApi.MAX_PDF_BYTES) {
                throw new IOException(
                        "This PDF exceeds the 20 MB limit."
                );
            }

            output.write(buffer, 0, count);
        }

        output.flush();
        return total;
    }

    private File pdfDirectory() {
        return new File(getCacheDir(), "slide_pdfs");
    }

    private String safeName(String name) {
        String cleaned = name
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .trim();

        if (cleaned.isEmpty()) {
            cleaned = "SlideBox";
        }

        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150);
        }

        if (!cleaned.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".pdf")) {
            cleaned += ".pdf";
        }

        return cleaned;
    }

    private void openPdf(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");

            intent.setClipData(
                    ClipData.newRawUri("SlideBox PDF", uri)
            );

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            startActivity(intent);

        } catch (ActivityNotFoundException e) {
            toast(
                    "No PDF viewer found. Use Save PDF instead."
            );
        } catch (Exception e) {
            toast(messageOf(e));
        }
    }

    private void setBusy(boolean value) {
        busy = value;

        if (btnLogin != null) {
            btnLogin.setEnabled(!value);
        }

        if (btnRegister != null) {
            btnRegister.setEnabled(!value);
        }

        if (btnGuest != null) {
            btnGuest.setEnabled(!value);
            btnGuest.setText(value
                    ? "Please wait..."
                    : "Download PDF without Login");
        }
    }

    private boolean screenActive() {
        return !isFinishing() && !isDestroyed();
    }

    private String messageOf(Exception e) {
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return "Check your internet connection and try again.";
        }

        String message = e.getMessage();

        return message == null || message.isEmpty()
                ? "Could not complete the request. Try again."
                : message;
    }

    private void toast(String message) {
        Toast.makeText(
                getApplicationContext(),
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    private void openDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.putExtra("name", accounts.getName());
        intent.putExtra("role", accounts.getRole());
        startActivity(intent);
        finish();
    }
}