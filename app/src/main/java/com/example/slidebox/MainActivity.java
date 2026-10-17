package com.example.slidebox;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private AccountStore accounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accounts = new AccountStore(this);

        if (accounts.isLoggedIn()) {
            openDashboard();
            return;
        }

        setContentView(R.layout.activity_main);

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

        Button btnLogin = findViewById(R.id.btnLogin);
        Button btnRegister = findViewById(R.id.btnRegister);

        btnLogin.setOnClickListener(view -> showLoginDialog());

        btnRegister.setOnClickListener(view ->
                startActivity(new Intent(
                        this, RegisterActivity.class
                ))
        );
    }

    private void showLoginDialog() {
        ContextThemeWrapper theme = new ContextThemeWrapper(
                this,
                androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert
        );

        LinearLayout form = new LinearLayout(theme);
        form.setOrientation(LinearLayout.VERTICAL);

        int padding = Math.round(
                24 * getResources().getDisplayMetrics().density
        );
        form.setPadding(padding, padding, padding, padding);

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

                loginButton.setEnabled(false);
                loginButton.setText("Checking...");
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        .setEnabled(false);

                new Thread(() -> {
                    boolean success = false;
                    String error = null;

                    try {
                        success = accounts.login(email, password);
                    } catch (Exception e) {
                        error = "Could not log in. Please try again.";
                    }

                    boolean loggedIn = success;
                    String message = error;

                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }

                        loginButton.setEnabled(true);
                        loginButton.setText("Login");
                        dialog.setCancelable(true);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                                .setEnabled(true);

                        if (loggedIn) {
                            dialog.dismiss();
                            openDashboard();
                        } else if (message != null) {
                            Toast.makeText(
                                    this, message, Toast.LENGTH_LONG
                            ).show();
                        } else {
                            passwordInput.setError(
                                    "Incorrect email or password"
                            );
                        }
                    });
                }).start();
            });
        });

        dialog.show();
    }

    private void openDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.putExtra("name", accounts.getName());
        intent.putExtra("role", accounts.getRole());
        startActivity(intent);
        finish();
    }
}