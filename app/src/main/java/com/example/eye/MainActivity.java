package com.example.eye; // Ensure this matches your package structure

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log; // Import Log for debugging
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity"; // For logging

    // Preference Keys (values stored in SECONDS)
    private static final String PREFS_NAME = "EyeBlinkerPrefs";
    private static final String KEY_WORK_TIME = "workTime";     // Stored in seconds
    private static final String KEY_BREAK_TIME = "breakTime";   // Stored in seconds
    private static final String KEY_VIBRATION_TIME = "vibrationTime"; // Stored in seconds

    // Default values (input for UI is in minutes for work/break, seconds for vibration)
    // These are converted to seconds for storage.
    private static final int DEFAULT_WORK_TIME_MIN = 20; // Default work time in minutes for UI
    private static final int DEFAULT_BREAK_TIME_MIN = 2;  // Default break time in minutes for UI
    private static final int DEFAULT_VIBRATION_TIME_SEC = 20; // Default vibration time in seconds

    private TextInputEditText workTimeEditText;     // Expects minutes
    private TextInputEditText breakTimeEditText;    // Expects minutes
    private TextInputEditText vibrationTimeEditText; // Expects seconds
    private MaterialButton startStopButton;
    private TextView statusTextView;
    private TextView timeTextView;
    private Toolbar toolbar;

    private boolean isServiceRunning = false;

    // Keys for Intent Extras (values sent to service in SECONDS)
    public static final String EXTRA_WORK_TIME_SEC = "com.example.eye.WORK_TIME_SEC";
    public static final String EXTRA_VIBRATION_TIME_SEC = "com.example.eye.VIBRATION_TIME_SEC";
    public static final String EXTRA_BREAK_TIME_SEC = "com.example.eye.BREAK_TIME_SEC";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        workTimeEditText = findViewById(R.id.workTimeEditText);
        breakTimeEditText = findViewById(R.id.breakTimeEditText);
        vibrationTimeEditText = findViewById(R.id.vibrationTimeEditText);
        startStopButton = findViewById(R.id.startStopButton);
        statusTextView = findViewById(R.id.statusTextView);
        timeTextView = findViewById(R.id.timeTextView);

        loadPreferences(); // Load and display preferences
        updateButtonUI();

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning) {
                    stopEyeBlinkerService();
                } else {
                    // savePreferences is called within startEyeBlinkerService
                    startEyeBlinkerService();
                }
            }
        });
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Load work time (stored in seconds), convert to minutes for UI
        int workTimeSec = prefs.getInt(KEY_WORK_TIME, DEFAULT_WORK_TIME_MIN * 60);
        workTimeEditText.setText(String.valueOf(workTimeSec / 60));

        // Load break time (stored in seconds), convert to minutes for UI
        int breakTimeSec = prefs.getInt(KEY_BREAK_TIME, DEFAULT_BREAK_TIME_MIN * 60);
        breakTimeEditText.setText(String.valueOf(breakTimeSec / 60));

        // Load vibration time (stored and displayed in seconds)
        int vibrationTimeSec = prefs.getInt(KEY_VIBRATION_TIME, DEFAULT_VIBRATION_TIME_SEC);
        vibrationTimeEditText.setText(String.valueOf(vibrationTimeSec));

        Log.d(TAG, "Loaded Preferences: Work (min): " + (workTimeSec / 60) +
                ", Break (min): " + (breakTimeSec / 60) +
                ", Vibration (sec): " + vibrationTimeSec);
    }

    private boolean savePreferences() {
        String workTimeMinStr = workTimeEditText.getText().toString();
        String breakTimeMinStr = breakTimeEditText.getText().toString();
        String vibrationTimeSecStr = vibrationTimeEditText.getText().toString();

        if (TextUtils.isEmpty(workTimeMinStr) || TextUtils.isEmpty(breakTimeMinStr) || TextUtils.isEmpty(vibrationTimeSecStr)) {
            Toast.makeText(this, "Please fill all time fields", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            int workTimeMin = Integer.parseInt(workTimeMinStr);
            int breakTimeMin = Integer.parseInt(breakTimeMinStr);
            int vibrationTimeSec = Integer.parseInt(vibrationTimeSecStr);

            if (workTimeMin <= 0 || breakTimeMin <= 0 || vibrationTimeSec <= 0) {
                Toast.makeText(this, "Time values must be positive", Toast.LENGTH_SHORT).show();
                return false;
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Convert minutes to seconds for storage
            editor.putInt(KEY_WORK_TIME, workTimeMin * 60);
            editor.putInt(KEY_BREAK_TIME, breakTimeMin * 60);
            editor.putInt(KEY_VIBRATION_TIME, vibrationTimeSec); // Already in seconds
            editor.apply();

            Log.d(TAG, "Saved Preferences: Work (sec): " + (workTimeMin * 60) +
                    ", Break (sec): " + (breakTimeMin * 60) +
                    ", Vibration (sec): " + vibrationTimeSec);
            return true;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void updateButtonUI() {
        if (isServiceRunning) {
            startStopButton.setText("Stop Blinker");
            startStopButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_stop_24));
        } else {
            startStopButton.setText("Start Blinker");
            startStopButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_play_arrow_24));
        }
    }

    private void startEyeBlinkerService() {
        if (!savePreferences()) { // Validate and save preferences before starting
            return;
        }

        Intent serviceIntent = new Intent(this, EyeBlinkerService.class);

        // Get values from UI (work/break in minutes, vibration in seconds)
        // Convert work/break times to SECONDS before sending to service
        try {
            int workTimeMin = Integer.parseInt(workTimeEditText.getText().toString());
            int breakTimeMin = Integer.parseInt(breakTimeEditText.getText().toString());
            int vibrationTimeSec = Integer.parseInt(vibrationTimeEditText.getText().toString());

            serviceIntent.putExtra(EXTRA_WORK_TIME_SEC, workTimeMin * 60);
            serviceIntent.putExtra(EXTRA_BREAK_TIME_SEC, breakTimeMin * 60);
            serviceIntent.putExtra(EXTRA_VIBRATION_TIME_SEC, vibrationTimeSec);

            Log.d(TAG, "Starting service with: Work (sec): " + (workTimeMin * 60) +
                    ", Break (sec): " + (breakTimeMin * 60) +
                    ", Vibration (sec): " + vibrationTimeSec);

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Error parsing time values before starting service.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "NumberFormatException while preparing intent extras", e);
            return; // Don't start service if parsing fails
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = true;
        updateButtonUI();
        statusTextView.setText("Status: Service Running");
    }

    private void stopEyeBlinkerService() {
        Intent serviceIntent = new Intent(this, EyeBlinkerService.class);
        stopService(serviceIntent);

        isServiceRunning = false;
        updateButtonUI();
        statusTextView.setText("Status: Stopped");
        timeTextView.setText("Health is Everything");
    }
}