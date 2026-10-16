package com.example.slidebox;

import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Toast;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class RegisterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

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
        Button btnCreateAccount = findViewById(R.id.btnCreateAccount);
        int darkText = android.graphics.Color.parseColor("#172033");
        int hintText = android.graphics.Color.parseColor("#596579");
        int blue = android.graphics.Color.parseColor("#243B80");

        etName.setTextColor(darkText);
        etEmail.setTextColor(darkText);
        etPassword.setTextColor(darkText);

        etName.setHintTextColor(hintText);
        etEmail.setHintTextColor(hintText);
        etPassword.setHintTextColor(hintText);

        etName.setTextSize(18);
        etEmail.setTextSize(18);
        etPassword.setTextSize(18);

        etName.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(blue)
        );
        etEmail.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(blue)
        );
        etPassword.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(blue)
        );

        RadioButton rbStudent = findViewById(R.id.rbStudent);

        rbStudent.setTextColor(darkText);
        rbTeacher.setTextColor(darkText);

        rbStudent.setButtonTintList(
                android.content.res.ColorStateList.valueOf(blue)
        );
        rbTeacher.setButtonTintList(
                android.content.res.ColorStateList.valueOf(blue)
        );

        btnCreateAccount.setTextColor(android.graphics.Color.WHITE);
        btnCreateAccount.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(blue)
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

            String role = rbTeacher.isChecked()
                    ? "Teacher"
                    : "Student";
            Intent intent = new Intent(
                    RegisterActivity.this,
                    DashboardActivity.class
            );

            intent.putExtra("name", name);
            intent.putExtra("role", role);

            startActivity(intent);

        });
    }
}