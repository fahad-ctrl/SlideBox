package com.example.slidebox;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.ActivityNotFoundException;
import android.widget.EditText;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DashboardActivity extends AppCompatActivity {

    private TextView tvWelcome;
    private SharedPreferences slides;
    private ArrayAdapter<String> slideAdapter;
    private Uri pdfToSave;

    private final ActivityResultLauncher<String> savePdfLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/pdf"
                    ),
                    destination -> {
                        Uri source = pdfToSave;
                        pdfToSave = null;

                        if (destination != null && source != null) {
                            copyPdfToFolder(source, destination);
                        }
                    }
            );

    // Keeps each list position matched with its slide code.
    private final List<String> slideCodes = new ArrayList<>();

    private final ActivityResultLauncher<String[]> pdfPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            saveSelectedPdf(uri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        slides = getSharedPreferences("slides", MODE_PRIVATE);

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
        tvWelcome = findViewById(R.id.tvWelcome);
        TextView tvListTitle = findViewById(R.id.tvListTitle);
        TextView tvEmpty = findViewById(R.id.tvEmpty);
        Button btnAction = findViewById(R.id.btnAction);
        ListView listSlides = findViewById(R.id.listSlides);

        int darkText = Color.parseColor("#172033");
        int blue = Color.parseColor("#243B80");

        tvTitle.setTextColor(blue);
        tvWelcome.setTextColor(darkText);
        tvListTitle.setTextColor(blue);
        tvEmpty.setTextColor(darkText);

        btnAction.setTextColor(Color.WHITE);
        btnAction.setBackgroundTintList(
                ColorStateList.valueOf(blue)
        );
        slideAdapter = new ArrayAdapter<String>(
                this,
                R.layout.item_slide,
                R.id.tvSlideName,
                new ArrayList<String>()
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

                TextView tvSlideName = row.findViewById(
                        R.id.tvSlideName
                );

                TextView tvSlideCode = row.findViewById(
                        R.id.tvSlideCode
                );

                String code = slideCodes.get(position);

                String fileName = slides.getString(
                        code + "_name",
                        "Selected PDF"
                );

                tvSlideName.setText(fileName);
                tvSlideCode.setText("Code: " + code);

                return row;
            }
        };

        listSlides.setAdapter(slideAdapter);

        String name = getIntent().getStringExtra("name");
        String role = getIntent().getStringExtra("role");

        tvWelcome.setText(
                "Welcome, " + (name == null ? "" : name)
        );

        if ("Teacher".equals(role)) {
            tvTitle.setText("Teacher Dashboard");
            btnAction.setText("Select PDF");
            tvListTitle.setText(
                    "Saved slides\nHold a slide to delete"
            );

            listSlides.setEmptyView(tvEmpty);
            loadSlideList();

            btnAction.setOnClickListener(view ->
                    pdfPicker.launch(
                            new String[]{"application/pdf"}
                    )
            );

            listSlides.setOnItemLongClickListener(
                    (parent, view, position, id) -> {
                        String code = slideCodes.get(position);
                        confirmDelete(code);
                        return true;
                    }
            );

        } else {
            tvTitle.setText("Student Dashboard");
            btnAction.setText("Enter Slide Code");

            tvListTitle.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
            listSlides.setVisibility(View.GONE);

            btnAction.setOnClickListener(view -> {
                showSlideCodeDialog();
            });

        }
    }

    private void loadSlideList() {
        slideCodes.clear();

        for (String key : slides.getAll().keySet()) {
            if (key.endsWith("_uri")) {
                String code = key.substring(
                        0,
                        key.length() - 4
                );

                slideCodes.add(code);
            }
        }

        Collections.sort(slideCodes, (first, second) -> {
            boolean firstNumeric = first.matches("[0-9]+");
            boolean secondNumeric = second.matches("[0-9]+");

            if (firstNumeric && secondNumeric) {
                if (first.length() != second.length()) {
                    return Integer.compare(
                            first.length(),
                            second.length()
                    );
                }

                return first.compareTo(second);
            }

            if (firstNumeric) {
                return -1;
            }

            if (secondNumeric) {
                return 1;
            }

            return first.compareTo(second);
        });

        slideAdapter.clear();

        for (String code : slideCodes) {
            String fileName = slides.getString(
                    code + "_name",
                    "Selected PDF"
            );

            slideAdapter.add(code + " — " + fileName);
        }

        slideAdapter.notifyDataSetChanged();
    }

    private void confirmDelete(String code) {
        String fileName = slides.getString(
                code + "_name",
                "Selected PDF"
        );

        new AlertDialog.Builder(this)
                .setTitle("Delete slide?")
                .setMessage(
                        "Code: " + code
                                + "\nPDF: " + fileName
                                + "\n\nRemove from SlideBox?"
                                + "\nThe original PDF will stay on your phone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    slides.edit()
                            .remove(code + "_uri")
                            .remove(code + "_name")
                            .apply();

                    loadSlideList();

                    Toast.makeText(
                            DashboardActivity.this,
                            "Slide removed",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
    }

    private void saveSelectedPdf(Uri uri) {
        String fileName = "Selected PDF";

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            try (Cursor cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                    );

                    if (index >= 0 && !cursor.isNull(index)) {
                        fileName = cursor.getString(index);
                    }
                }
            }

            // Keep increasing codes so deleted codes are not reused.
            long number = slides.getLong("next_code", 1L);

            // Also account for slides saved by the previous version.
            for (String key : slides.getAll().keySet()) {
                if (key.endsWith("_uri")) {
                    String savedCode = key.substring(
                            0,
                            key.length() - 4
                    );

                    try {
                        long savedNumber = Long.parseLong(savedCode);
                        if (savedNumber >= number) {
                            number = savedNumber + 1;
                        }
                    } catch (NumberFormatException ignored) {
                        // Older letter-based codes are kept unchanged.
                    }
                }
            }

            String code = String.valueOf(number);

            slides.edit()
                    .putString(code + "_uri", uri.toString())
                    .putString(code + "_name", fileName)
                    .putLong("next_code", number + 1)
                    .apply();

            loadSlideList();

            new AlertDialog.Builder(this)
                    .setTitle("PDF saved for demo")
                    .setMessage(
                            "PDF: " + fileName
                                    + "\nCode: " + code
                    )
                    .setPositiveButton("OK", null)
                    .show();

        } catch (Exception e) {
            Toast.makeText(
                    DashboardActivity.this,
                    "Could not save PDF access. Please select again.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
    private void showSlideCodeDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter slide code");
        input.setSingleLine(true);
        input.setTextColor(Color.parseColor("#172033"));
        input.setHintTextColor(Color.parseColor("#596579"));
        input.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#243B80"))
        );

        int padding = Math.round(
                20 * getResources().getDisplayMetrics().density
        );

        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Find a slide")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Save PDF", null)
                .setPositiveButton("Open PDF", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        String code = input.getText()
                                .toString().trim();

                        if (code.isEmpty()) {
                            input.setError("Enter a code");
                            return;
                        }

                        String uriText = slides.getString(
                                code + "_uri", null
                        );

                        if (uriText == null) {
                            input.setError("No slide found for this code");
                            return;
                        }

                        openSavedPdf(Uri.parse(uriText));
                        dialog.dismiss();
                    });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> {
                        String code = input.getText()
                                .toString().trim();

                        if (code.isEmpty()) {
                            input.setError("Enter a code");
                            return;
                        }

                        String uriText = slides.getString(
                                code + "_uri", null
                        );

                        if (uriText == null) {
                            input.setError("No slide found for this code");
                            return;
                        }

                        String fileName = slides.getString(
                                code + "_name", "Slide.pdf"
                        );

                        pdfToSave = Uri.parse(uriText);
                        savePdfLauncher.launch(fileName);
                        dialog.dismiss();
                    });
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.white
            );
        }
    }

    private void openSavedPdf(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    this,
                    "No PDF viewer found on this phone",
                    Toast.LENGTH_LONG
            ).show();
        } catch (SecurityException e) {
            Toast.makeText(
                    this,
                    "PDF access was lost. Ask the teacher to select it again.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
    private void copyPdfToFolder(Uri source, Uri destination) {
        Toast.makeText(
                this,
                "Saving PDF...",
                Toast.LENGTH_SHORT
        ).show();

        new Thread(() -> {
            try (
                    InputStream input =
                            getContentResolver().openInputStream(source);

                    OutputStream output =
                            getContentResolver().openOutputStream(
                                    destination, "w"
                            )
            ) {
                if (input == null || output == null) {
                    throw new java.io.IOException(
                            "Could not open the PDF"
                    );
                }

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }

                output.flush();

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(
                                DashboardActivity.this,
                                "Could not save PDF. Check file access and free space.",
                                Toast.LENGTH_LONG
                        ).show()
                );
                return;
            }

            runOnUiThread(() ->
                    Toast.makeText(
                            DashboardActivity.this,
                            "PDF saved successfully",
                            Toast.LENGTH_LONG
                    ).show()
            );
        }).start();
    }
}