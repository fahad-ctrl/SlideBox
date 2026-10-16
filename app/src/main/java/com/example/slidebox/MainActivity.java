package com.example.slidebox;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep content clear of the status and navigation bars.
        int padding = Math.round(
                24 * getResources().getDisplayMetrics().density
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (view, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );

                    view.setPadding(
                            padding + bars.left,
                            padding + bars.top,
                            padding + bars.right,
                            padding + bars.bottom
                    );

                    return windowInsets;
                }
        );

        Button btnLogin = findViewById(R.id.btnLogin);
        Button btnRegister = findViewById(R.id.btnRegister);

        btnLogin.setOnClickListener(view ->
                Toast.makeText(
                        MainActivity.this,
                        "Login screen coming next",
                        Toast.LENGTH_SHORT
                ).show()
        );

        btnRegister.setOnClickListener(view -> {
            Intent intent = new Intent(
                    MainActivity.this,
                    RegisterActivity.class
            );
            startActivity(intent);
        });
    }
}