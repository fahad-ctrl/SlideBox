package com.example.slidebox;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity {

    private AccountStore accounts;
    private SharedPreferences deleteProgress;

    private String currentUserId;
    private boolean teacher;
    private boolean busy;

    private Button btnAction;
    private Button btnLogout;
    private Button btnRefresh;
    private ListView listSlides;
    private TextView tvEmpty;

    private ArrayAdapter<Slide> slideAdapter;
    private File pdfToSave;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private static final String SLIDE_FIELDS =
            "code,teacher_id,file_name,storage_path";

    private static final class Slide {

        final long code;
        final String teacherId;
        final String name;
        final String path;

        Slide(JSONObject row) throws Exception {
            code = row.getLong("code");
            teacherId = row.getString("teacher_id");
            name = row.getString("file_name");
            path = row.getString("storage_path");

            if (code <= 0 || path.isEmpty()) {
                throw new IOException("Invalid slide information.");
            }
        }
    }

    private interface Task<T> {
        T run() throws Exception;
    }

    private interface Result<T> {
        void accept(T value) throws Exception;
    }

    private interface TokenTask<T> {
        T run(String token) throws Exception;
    }

    private final ActivityResultLauncher<String[]> pdfPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            uploadSelectedPdf(uri);
                        }
                    }
            );

    private final ActivityResultLauncher<String> savePdfLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/pdf"
                    ),
                    destination -> {
                        File source = pdfToSave;
                        pdfToSave = null;

                        if (destination == null) {
                            if (source != null) {
                                source.delete();
                            }
                            return;
                        }

                        if (source == null || !source.isFile()) {
                            showMessage(
                                    "Temporary PDF is missing. "
                                            + "Find the slide again."
                            );
                            return;
                        }

                        runTask(
                                "Saving...",
                                () -> {
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
                                        output.flush();
                                    } finally {
                                        source.delete();
                                    }

                                    return true;
                                },
                                saved -> showMessage(
                                        "PDF saved successfully."
                                )
                        );
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accounts = new AccountStore(this);

        if (!accounts.isLoggedIn()) {
            returnToLogin();
            return;
        }

        setContentView(R.layout.activity_dashboard);

        currentUserId = accounts.getUserId();
        teacher = "Teacher".equals(accounts.getRole());

        deleteProgress = getSharedPreferences(
                "online_delete_progress",
                MODE_PRIVATE
        );

        if (savedInstanceState != null) {
            String pending = savedInstanceState.getString(
                    "pending_pdf_name"
            );

            // Restore only a file inside our PDF cache folder.
            if (pending != null
                    && pending.equals(new File(pending).getName())) {
                File file = new File(pdfDirectory(), pending);

                if (file.isFile()) {
                    pdfToSave = file;
                }
            }
        }

        int padding = Math.round(
                24 * getResources().getDisplayMetrics().density
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
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

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvWelcome = findViewById(R.id.tvWelcome);
        TextView tvListTitle = findViewById(R.id.tvListTitle);

        tvEmpty = findViewById(R.id.tvEmpty);
        btnAction = findViewById(R.id.btnAction);
        listSlides = findViewById(R.id.listSlides);

        int dark = Color.parseColor("#172033");
        int blue = Color.parseColor("#243B80");

        tvTitle.setTextColor(blue);
        tvWelcome.setTextColor(dark);
        tvListTitle.setTextColor(blue);
        tvEmpty.setTextColor(dark);

        btnAction.setTextColor(Color.WHITE);
        btnAction.setBackgroundTintList(
                ColorStateList.valueOf(blue)
        );

        ViewGroup root = findViewById(R.id.main);

        btnLogout = new Button(this);
        btnLogout.setText("Logout");
        root.addView(btnLogout, 0);

        btnLogout.setOnClickListener(view -> {
            if (!busy) {
                accounts.logout();
                returnToLogin();
            }
        });

        btnRefresh = new Button(this);
        btnRefresh.setText("Refresh slides");
        root.addView(btnRefresh, 1);

        btnRefresh.setOnClickListener(view -> loadSlideList());

        slideAdapter = new ArrayAdapter<Slide>(
                this,
                R.layout.item_slide,
                R.id.tvSlideName,
                new ArrayList<>()
        ) {
            @Override
            public View getView(
                    int position,
                    View convertView,
                    ViewGroup parent
            ) {
                View row = convertView;

                if (row == null) {
                    row = getLayoutInflater().inflate(
                            R.layout.item_slide,
                            parent,
                            false
                    );
                }

                Slide slide = getItem(position);

                TextView name = row.findViewById(
                        R.id.tvSlideName
                );

                TextView code = row.findViewById(
                        R.id.tvSlideCode
                );

                if (slide != null) {
                    name.setText(slide.name);
                    code.setText("Code: " + slide.code);
                }

                return row;
            }
        };

        listSlides.setAdapter(slideAdapter);
        tvWelcome.setText("Welcome, " + accounts.getName());

        if (teacher) {
            tvTitle.setText("Teacher Dashboard");
            btnAction.setText("Upload PDF");

            tvListTitle.setText(
                    "My online slides\nTap for options • Hold to delete"
            );

            tvEmpty.setText("Loading online slides...");
            listSlides.setEmptyView(tvEmpty);

            btnAction.setOnClickListener(view -> {
                if (!busy) {
                    pdfPicker.launch(
                            new String[]{"application/pdf"}
                    );
                }
            });

            listSlides.setOnItemClickListener(
                    (parent, view, position, id) -> {
                        if (!busy) {
                            showSlideOptions(
                                    slideAdapter.getItem(position)
                            );
                        }
                    }
            );

            listSlides.setOnItemLongClickListener(
                    (parent, view, position, id) -> {
                        if (!busy) {
                            confirmDelete(
                                    slideAdapter.getItem(position)
                            );
                        }

                        return true;
                    }
            );

            loadSlideList();

        } else {
            tvTitle.setText("Student Dashboard");
            btnAction.setText("Enter Slide Code");

            btnRefresh.setVisibility(View.GONE);
            tvListTitle.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
            listSlides.setVisibility(View.GONE);

            btnAction.setOnClickListener(view -> {
                if (!busy) {
                    showSlideCodeDialog();
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (pdfToSave != null) {
            outState.putString(
                    "pending_pdf_name",
                    pdfToSave.getName()
            );
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        // Let an already-started transfer finish.
        worker.shutdown();
        super.onDestroy();
    }

    private boolean screenActive() {
        return !isFinishing()
                && !isDestroyed()
                && accounts.isLoggedIn()
                && currentUserId.equals(accounts.getUserId());
    }

    private void checkAccount() throws IOException {
        if (!accounts.isLoggedIn()
                || !currentUserId.equals(accounts.getUserId())) {
            throw new AccountStore.SessionExpiredException();
        }
    }

    private <T> T authenticated(TokenTask<T> action)
            throws Exception {

        checkAccount();
        String token = accounts.getAccessToken();
        checkAccount();

        try {
            return action.run(token);

        } catch (SupabaseApi.ApiException e) {
            if (e.statusCode != 401) {
                throw e;
            }

            String refreshed = accounts.refreshAccessToken(token);
            checkAccount();

            // Retry once, only after an explicit authentication failure.
            return action.run(refreshed);
        }
    }

    private String database(
            String method,
            String path,
            JSONObject body,
            String prefer
    ) throws Exception {

        return authenticated(token ->
                SupabaseApi.request(
                        method,
                        path,
                        body,
                        token,
                        prefer
                )
        );
    }

    private <T> void runTask(
            String label,
            Task<T> task,
            Result<T> result
    ) {
        if (busy || isFinishing() || isDestroyed()) {
            return;
        }

        setBusy(true, label);

        worker.execute(() -> {
            try {
                T value = task.run();

                runOnUiThread(() -> {
                    if (!screenActive()) {
                        if (value instanceof File) {
                            ((File) value).delete();
                        }
                        return;
                    }

                    setBusy(false, "");

                    try {
                        result.accept(value);
                    } catch (Exception e) {
                        showError(e);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    setBusy(false, "");
                    showError(e);
                });
            }
        });
    }

    private void setBusy(boolean value, String label) {
        busy = value;

        btnAction.setEnabled(!value);
        btnLogout.setEnabled(!value);
        btnRefresh.setEnabled(!value);
        listSlides.setEnabled(!value);

        btnAction.setText(
                value
                        ? label
                        : teacher ? "Upload PDF" : "Enter Slide Code"
        );
    }

    private void loadSlideList() {
        if (!teacher || busy) {
            return;
        }

        tvEmpty.setText("Loading online slides...");

        runTask(
                "Loading...",
                () -> {
                    List<Slide> result = new ArrayList<>();
                    long lastCode = 0L;

                    // Read pages so the list is not limited to one response.
                    while (true) {
                        String path = "/rest/v1/slides"
                                + "?teacher_id=eq."
                                + Uri.encode(currentUserId)
                                + "&select=" + SLIDE_FIELDS
                                + "&code=gt." + lastCode
                                + "&order=code.asc"
                                + "&limit=100";

                        JSONArray rows = new JSONArray(
                                database("GET", path, null, null)
                        );

                        if (rows.length() == 0) {
                            break;
                        }

                        for (int i = 0; i < rows.length(); i++) {
                            Slide slide = new Slide(
                                    rows.getJSONObject(i)
                            );

                            result.add(slide);
                            lastCode = slide.code;
                        }
                    }

                    return result;
                },
                result -> {
                    slideAdapter.setNotifyOnChange(false);
                    slideAdapter.clear();
                    slideAdapter.addAll(result);
                    slideAdapter.notifyDataSetChanged();

                    tvEmpty.setText(
                            "No online slides yet.\nTap Upload PDF to begin."
                    );
                }
        );
    }

    private String selectedName(Uri uri) {
        String name = "Slide.pdf";

        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0 && !cursor.isNull(index)) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }

        return safeName(name);
    }

    private String safeName(String name) {
        if (name == null) {
            return "Slide.pdf";
        }

        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .trim();

        if (name.isEmpty()) {
            name = "Slide.pdf";
        }

        if (name.length() > 160) {
            name = name.substring(0, 160);
        }

        if (!name.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".pdf")) {
            name += ".pdf";
        }

        return name;
    }

    private void uploadSelectedPdf(Uri uri) {
        if (!teacher || busy) {
            return;
        }

        runTask(
                "Uploading...",
                () -> {
                    checkAccount();

                    String name = selectedName(uri);

                    File temporary = File.createTempFile(
                            "upload-",
                            ".pdf",
                            getCacheDir()
                    );

                    String storagePath = currentUserId
                            + "/"
                            + UUID.randomUUID()
                            + ".pdf";

                    try {
                        try (
                                InputStream input =
                                        getContentResolver()
                                                .openInputStream(uri);
                                OutputStream output =
                                        new FileOutputStream(temporary)
                        ) {
                            if (input == null) {
                                throw new IOException(
                                        "Could not read selected PDF."
                                );
                            }

                            copyPdf(input, output);
                        }

                        if (temporary.length() == 0L) {
                            throw new IOException("Selected PDF is empty.");
                        }

                        authenticated(token ->
                                SupabaseApi.uploadPdf(
                                        storagePath,
                                        temporary,
                                        token
                                )
                        );

                        JSONObject body = new JSONObject();
                        body.put("teacher_id", currentUserId);
                        body.put("file_name", name);
                        body.put("storage_path", storagePath);

                        try {
                            JSONArray inserted = new JSONArray(
                                    database(
                                            "POST",
                                            "/rest/v1/slides?select="
                                                    + SLIDE_FIELDS,
                                            body,
                                            "return=representation"
                                    )
                            );

                            if (inserted.length() != 1) {
                                throw new IOException(
                                        "Slide code was not returned."
                                );
                            }

                            return new Slide(inserted.getJSONObject(0));

                        } catch (Exception insertError) {
                            // The insert may have succeeded even if its
                            // response was lost. Check before retrying.
                            try {
                                JSONArray recovered = new JSONArray(
                                        database(
                                                "GET",
                                                "/rest/v1/slides"
                                                        + "?storage_path=eq."
                                                        + Uri.encode(storagePath)
                                                        + "&select=" + SLIDE_FIELDS
                                                        + "&limit=1",
                                                null,
                                                null
                                        )
                                );

                                if (recovered.length() == 1) {
                                    return new Slide(
                                            recovered.getJSONObject(0)
                                    );
                                }
                            } catch (Exception ignored) {
                            }

                            // Do not delete an object whose database
                            // insert status is uncertain.
                            throw new IOException(
                                    "PDF uploaded, but saving its code "
                                            + "could not be confirmed. "
                                            + "Tap Refresh slides before "
                                            + "uploading again. If absent, "
                                            + "check Supabase slides/Storage. "
                                            + "Details: "
                                            + insertError.getMessage(),
                                    insertError
                            );
                        }

                    } finally {
                        temporary.delete();
                    }
                },
                slide -> {
                    loadSlideList();

                    new AlertDialog.Builder(this)
                            .setTitle("PDF uploaded")
                            .setMessage(
                                    "PDF: " + slide.name
                                            + "\n\nSlide code: " + slide.code
                                            + "\n\nShare this code with students."
                            )
                            .setPositiveButton("OK", null)
                            .show();
                }
        );
    }

    private void showSlideOptions(Slide slide) {
        if (slide == null) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Code: " + slide.code)
                .setItems(
                        new String[]{"Open PDF", "Save PDF"},
                        (dialog, which) ->
                                fetchPdf(slide.code, which == 1)
                )
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSlideCodeDialog() {
        ContextThemeWrapper theme = new ContextThemeWrapper(
                this,
                androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert
        );

        EditText input = new EditText(theme);
        input.setHint("Enter slide code");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(Color.parseColor("#172033"));

        int padding = Math.round(
                20 * getResources().getDisplayMetrics().density
        );
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(theme)
                .setTitle("Find online slide")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Save PDF", null)
                .setPositiveButton("Open PDF", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view ->
                            submitCode(input, dialog, false)
                    );

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view ->
                            submitCode(input, dialog, true)
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

        try {
            if (!text.matches("[0-9]+")) {
                throw new NumberFormatException();
            }

            long code = Long.parseLong(text);

            if (code <= 0) {
                throw new NumberFormatException();
            }

            dialog.dismiss();
            fetchPdf(code, save);

        } catch (NumberFormatException e) {
            input.setError("Enter a valid slide code");
        }
    }

    private Slide findSlide(long code) throws Exception {
        JSONArray rows = new JSONArray(
                database(
                        "GET",
                        "/rest/v1/slides?code=eq." + code
                                + "&select=" + SLIDE_FIELDS
                                + "&limit=1",
                        null,
                        null
                )
        );

        if (rows.length() == 0) {
            throw new IOException(
                    "No online slide found for code " + code + "."
            );
        }

        return new Slide(rows.getJSONObject(0));
    }

    private File pdfDirectory() {
        return new File(getCacheDir(), "slide_pdfs");
    }

    private void fetchPdf(long code, boolean save) {
        final String[] fileName = {"Slide.pdf"};

        runTask(
                "Downloading...",
                () -> {
                    Slide slide = findSlide(code);
                    fileName[0] = safeName(slide.name);

                    return authenticated(token ->
                            SupabaseApi.downloadPdf(
                                    slide.path,
                                    pdfDirectory(),
                                    token
                            )
                    );
                },
                file -> {
                    if (save) {
                        pdfToSave = file;

                        try {
                            savePdfLauncher.launch(fileName[0]);
                        } catch (Exception e) {
                            pdfToSave = null;
                            file.delete();
                            throw e;
                        }
                    } else {
                        openPdf(file);
                    }
                }
        );
    }

    private void openPdf(File file) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.setClipData(ClipData.newRawUri("PDF", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showMessage(
                    "No PDF viewer found. Use Save PDF, "
                            + "or install a PDF viewer."
            );
        }
    }

    private boolean owns(Slide slide) {
        return slide != null
                && teacher
                && currentUserId.equals(slide.teacherId)
                && slide.path.startsWith(currentUserId + "/");
    }

    private void confirmDelete(Slide slide) {
        if (!owns(slide)) {
            showMessage("You can only delete your own slides.");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete online slide?")
                .setMessage(
                        "Code: " + slide.code
                                + "\nPDF: " + slide.name
                                + "\n\nStudents will no longer be able "
                                + "to download it using this code."
                                + "\nPreviously saved copies will remain."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Delete",
                        (dialog, which) -> deleteSlide(slide)
                )
                .show();
    }

    private void deleteSlide(Slide slide) {
        if (!owns(slide)) {
            return;
        }

        runTask(
                "Deleting...",
                () -> {
                    checkAccount();

                    String marker = slide.path;

                    // Remember confirmed Storage deletion so a failed
                    // database deletion can be retried later.
                    if (!deleteProgress.getBoolean(marker, false)) {
                        String response = authenticated(token ->
                                SupabaseApi.deletePdf(
                                        slide.path,
                                        token
                                )
                        );

                        JSONArray removed = new JSONArray(response);
                        boolean confirmed = false;

                        for (int i = 0; i < removed.length(); i++) {
                            JSONObject object = removed.getJSONObject(i);

                            if (slide.path.equals(
                                    object.optString("name", "")
                            )) {
                                confirmed = true;
                                break;
                            }
                        }

                        if (!confirmed) {
                            throw new IOException(
                                    "Storage deletion was not confirmed. "
                                            + "Check Storage SELECT/DELETE "
                                            + "policies and whether the PDF "
                                            + "still exists."
                            );
                        }

                        if (!deleteProgress.edit()
                                .putBoolean(marker, true)
                                .commit()) {
                            throw new IOException(
                                    "PDF removed from Storage, but progress "
                                            + "could not be saved. Check the "
                                            + "slide record in Supabase."
                            );
                        }
                    }

                    try {
                        database(
                                "DELETE",
                                "/rest/v1/slides?code=eq." + slide.code
                                        + "&teacher_id=eq."
                                        + Uri.encode(currentUserId),
                                null,
                                "return=representation"
                        );

                        JSONArray remaining = new JSONArray(
                                database(
                                        "GET",
                                        "/rest/v1/slides?code=eq."
                                                + slide.code
                                                + "&select=code&limit=1",
                                        null,
                                        null
                                )
                        );

                        if (remaining.length() != 0) {
                            throw new IOException(
                                    "Slide record still exists."
                            );
                        }

                    } catch (Exception e) {
                        throw new IOException(
                                "PDF removed from Storage, but deleting "
                                        + "the slide record could not be "
                                        + "confirmed. Try Delete again. "
                                        + "Details: " + e.getMessage(),
                                e
                        );
                    }

                    deleteProgress.edit().remove(marker).apply();
                    return true;
                },
                removed -> {
                    showMessage("Online slide deleted.");
                    loadSlideList();
                }
        );
    }

    private void copyPdf(InputStream input, OutputStream output)
            throws IOException {

        byte[] buffer = new byte[8192];
        long total = 0L;
        int count;

        while ((count = input.read(buffer)) != -1) {
            total += count;

            if (total > SupabaseApi.MAX_PDF_BYTES) {
                throw new IOException(
                        "Select a PDF smaller than 20 MB."
                );
            }

            output.write(buffer, 0, count);
        }
    }

    private void showError(Exception error) {
        if (!accounts.isLoggedIn()
                || error instanceof AccountStore.SessionExpiredException) {
            showMessage("Session expired. Please log in again.");
            returnToLogin();
            return;
        }

        if (teacher) {
            tvEmpty.setText(
                    "Could not complete the request.\nTap Refresh slides."
            );
        }

        if (error instanceof java.net.UnknownHostException
                || error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.ConnectException) {
            showMessage(
                    "Connection failed. Check internet and try again."
            );
            return;
        }

        String message = error.getMessage();

        showMessage(
                message == null || message.isEmpty()
                        ? "Could not complete the request."
                        : message
        );
    }

    private void returnToLogin() {
        Intent intent = new Intent(this, MainActivity.class);

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );

        startActivity(intent);
        finish();
    }

    private void showMessage(String message) {
        Toast.makeText(
                getApplicationContext(),
                message,
                Toast.LENGTH_LONG
        ).show();
    }
}