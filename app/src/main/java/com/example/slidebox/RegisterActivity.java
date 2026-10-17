package com.example.slidebox;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class RegisterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        AccountStore accounts = new AccountStore(this);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (view, insets) -> {
                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.ime()
                    );

                    view.setPadding(
                            bars.left,
                            bars.top,
                            bars.right,
                            bars.bottom
                    );

                    return insets;
                }
        );

        EditText etName = findViewById(R.id.etName);
        EditText etEmail = findViewById(R.id.etEmail);
        EditText etPassword = findViewById(R.id.etPassword);

        RadioButton rbTeacher = findViewById(R.id.rbTeacher);
        RadioButton rbStudent = findViewById(R.id.rbStudent);

        Button btnCreateAccount = findViewById(
                R.id.btnCreateAccount
        );

        int darkText = Color.parseColor("#172033");
        int hintText = Color.parseColor("#596579");
        int blue = Color.parseColor("#243B80");

        for (EditText field : new EditText[]{
                etName, etEmail, etPassword
        }) {
            field.setTextColor(darkText);
            field.setHintTextColor(hintText);
            field.setTextSize(18);
            field.setBackgroundTintList(
                    ColorStateList.valueOf(blue)
            );
        }

        rbStudent.setTextColor(darkText);
        rbTeacher.setTextColor(darkText);

        rbStudent.setButtonTintList(
                ColorStateList.valueOf(blue)
        );

        rbTeacher.setButtonTintList(
                ColorStateList.valueOf(blue)
        );

        btnCreateAccount.setTextColor(Color.WHITE);
        btnCreateAccount.setBackgroundTintList(
                ColorStateList.valueOf(blue)
        );

        btnCreateAccount.setOnClickListener(view -> {
            String name = etName.getText().toString().trim();
            String email = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString();

            if (name.isEmpty()) {
                etName.setError("Enter your name");
                etName.requestFocus();
                return;
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etEmail.setError("Enter a valid email");
                etEmail.requestFocus();
                return;
            }

            if (password.length() < 6) {
                etPassword.setError("Use at least 6 characters");
                etPassword.requestFocus();
                return;
            }

            if (!rbTeacher.isChecked() && !rbStudent.isChecked()) {
                Toast.makeText(
                        this,
                        "Select Teacher or Student",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            String role = rbTeacher.isChecked()
                    ? "Teacher"
                    : "Student";

            btnCreateAccount.setEnabled(false);
            btnCreateAccount.setText("Creating...");

            etName.setEnabled(false);
            etEmail.setEnabled(false);
            etPassword.setEnabled(false);
            rbTeacher.setEnabled(false);
            rbStudent.setEnabled(false);

            new Thread(() -> {
                String error = null;

                try {
                    accounts.register(
                            name,
                            email,
                            password,
                            role
                    );
                } catch (Exception e) {
                    error = AccountStore.registrationError(e);
                }

                String message = error;

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    btnCreateAccount.setEnabled(true);
                    btnCreateAccount.setText("Create Account");

                    etName.setEnabled(true);
                    etEmail.setEnabled(true);
                    etPassword.setEnabled(true);
                    rbTeacher.setEnabled(true);
                    rbStudent.setEnabled(true);

                    if (message != null) {
                        Toast.makeText(
                                this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    } else {
                        Toast.makeText(
                                this,
                                "Online account created. Please log in.",
                                Toast.LENGTH_LONG
                        ).show();

                        finish();
                    }
                });
            }).start();
        });
    }
}