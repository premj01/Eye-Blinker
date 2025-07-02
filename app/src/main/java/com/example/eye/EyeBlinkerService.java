package com.example.eye; // Ensure this matches your package structure

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class EyeBlinkerService extends Service {

    private static final String TAG = "EyeBlinkerService";
    public static final String NOTIFICATION_CHANNEL_ID = "EyeBlinkerChannel";
    public static final int NOTIFICATION_ID = 1;

    // Default times in SECONDS
    private static final int DEFAULT_WORK_TIME_SERVICE_SEC = 20 * 60; // 20 minutes
    private static final int DEFAULT_VIBRATION_DURATION_SEC = 10;     // Default for continuous vibration after work
    private static final int DEFAULT_BREAK_REST_SERVICE_SEC = 2 * 60; // 2 minutes

    // Variables to hold the current timer durations in MILLISECONDS
    private long currentWorkTimeMillis;
    private long currentContinuousVibrationDurationMillis; // For the vibration after work (configurable)
    private long currentBreakRestMillis;

    private CountDownTimer workTimer;
    private CountDownTimer breakRestTimer;
    private Vibrator vibrator;

    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public static final String EXTRA_WORK_TIME_SEC = "com.example.eye.WORK_TIME_SEC";
    public static final String EXTRA_VIBRATION_TIME_SEC = "com.example.eye.VIBRATION_TIME_SEC"; // User configured duration for post-work vibe
    public static final String EXTRA_BREAK_TIME_SEC = "com.example.eye.BREAK_TIME_SEC";

    // Define the FIXED interval pattern for "Back to Work!" (after break)
    // Pattern: Vibrate 500ms, Pause 500ms, Vibrate 500ms, Pause 500ms...
    // Total 5 cycles = 5 * (500ms on + 500ms off) = 5000ms = 5 seconds
    private static final long[] BACK_TO_WORK_INTERVAL_PATTERN = {
            0,   // Start immediately
            500, // Vibrate for 500ms
            500, // Pause for 500ms
            500, // Vibrate for 500ms
            500, // Pause for 500ms
            500, // Vibrate for 500ms
            500, // Pause for 500ms
            500, // Vibrate for 500ms
            500, // Pause for 500ms
            500  // Vibrate for 500ms (pattern ends after this)
    };

    public enum VibrationFeedbackType {
        CONTINUOUS_AFTER_WORK,  // For the one-shot, user-duration vibration when break starts
        INTERVAL_BACK_TO_WORK   // For the fixed interval pattern when rest ends
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        if (intent != null) {
            int workTimeSec = intent.getIntExtra(EXTRA_WORK_TIME_SEC, DEFAULT_WORK_TIME_SERVICE_SEC);
            int continuousVibrationSec = intent.getIntExtra(EXTRA_VIBRATION_TIME_SEC, DEFAULT_VIBRATION_DURATION_SEC);
            int breakTimeSec = intent.getIntExtra(EXTRA_BREAK_TIME_SEC, DEFAULT_BREAK_REST_SERVICE_SEC);

            currentWorkTimeMillis = workTimeSec * 1000L;
            currentContinuousVibrationDurationMillis = continuousVibrationSec * 1000L;
            currentBreakRestMillis = breakTimeSec * 1000L;

            Log.d(TAG, "Using times from intent: Work=" + currentWorkTimeMillis / 1000 + "s, " +
                    "ContinuousVibeAfterWork=" + currentContinuousVibrationDurationMillis / 1000 + "s, " +
                    "BreakRest=" + currentBreakRestMillis / 1000 + "s");
        } else {
            Log.w(TAG, "Intent was null in onStartCommand. Using service default times.");
            currentWorkTimeMillis = DEFAULT_WORK_TIME_SERVICE_SEC * 1000L;
            currentContinuousVibrationDurationMillis = DEFAULT_VIBRATION_DURATION_SEC * 1000L;
            currentBreakRestMillis = DEFAULT_BREAK_REST_SERVICE_SEC * 1000L;
        }

        if (workTimer != null) workTimer.cancel();
        if (breakRestTimer != null) breakRestTimer.cancel();

        Notification notification = createNotification("Eye Blinker Active - Preparing...");
        startForeground(NOTIFICATION_ID, notification);
        startWorkCycle();
        return START_STICKY;
    }

    /**
     * Triggers a vibration feedback based on the specified type.
     * @param type The type of vibration to play.
     * @param toastMessage The message to display in a Toast.
     */
    private void triggerVibrationFeedback(VibrationFeedbackType type, String toastMessage) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "No vibrator available or permission missing.");
            if (toastMessage != null && !toastMessage.isEmpty()) {
                mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, toastMessage, Toast.LENGTH_SHORT).show());
            }
            return;
        }

        VibrationEffect effect = null;
        String logMessageDetails = "";

        if (type == VibrationFeedbackType.CONTINUOUS_AFTER_WORK) {
            if (currentContinuousVibrationDurationMillis <= 0) { // Ensure duration is positive
                Log.w(TAG, "Continuous vibration duration is zero or negative, skipping vibration.");
                if (toastMessage != null && !toastMessage.isEmpty()) { // Still show toast
                    mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, toastMessage, Toast.LENGTH_SHORT).show());
                }
                return;
            }
            logMessageDetails = "Playing continuous vibration after work (" + currentContinuousVibrationDurationMillis / 1000.0 + "s).";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                effect = VibrationEffect.createOneShot(currentContinuousVibrationDurationMillis, VibrationEffect.DEFAULT_AMPLITUDE);
            } else {
                // Legacy API for one-shot
                Log.d(TAG, logMessageDetails + " Message: " + toastMessage);
                vibrator.vibrate(currentContinuousVibrationDurationMillis);
                if (toastMessage != null && !toastMessage.isEmpty()) {
                    mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, toastMessage, Toast.LENGTH_SHORT).show());
                }
                return; // Vibration started, return
            }
        } else if (type == VibrationFeedbackType.INTERVAL_BACK_TO_WORK) {
            logMessageDetails = "Playing 'back to work' interval pattern (approx 5s).";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                effect = VibrationEffect.createWaveform(BACK_TO_WORK_INTERVAL_PATTERN, -1); // -1 for no repeat
            } else {
                // Legacy API for pattern
                Log.d(TAG, logMessageDetails + " Message: " + toastMessage);
                vibrator.vibrate(BACK_TO_WORK_INTERVAL_PATTERN, -1);
                if (toastMessage != null && !toastMessage.isEmpty()) {
                    mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, toastMessage, Toast.LENGTH_SHORT).show());
                }
                return; // Vibration started, return
            }
        }

        // For Android O and above, effect will be non-null if one was created
        if (effect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, logMessageDetails + " Message: " + toastMessage);
            vibrator.vibrate(effect);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && type == null) {
            // Should not happen with current logic, but a fallback log
            Log.w(TAG, "Vibration type not handled for legacy Android or effect is null.");
        }


        if (toastMessage != null && !toastMessage.isEmpty()) {
            // Toast for API O+ (legacy handles its own toast before returning)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || (effect == null && type != null)) {
                mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, toastMessage, Toast.LENGTH_SHORT).show());
            }
        }
    }


    private void startWorkCycle() {
        Log.d(TAG, "Starting work cycle for " + currentWorkTimeMillis / 1000 + "s");
        updateNotification("Status: Working...");
        if (workTimer != null) workTimer.cancel();
        workTimer = new CountDownTimer(currentWorkTimeMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateNotification("Working: " + formatTime(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "Work time finished. Signaling start of break with continuous vibration.");
                triggerVibrationFeedback(VibrationFeedbackType.CONTINUOUS_AFTER_WORK, "Break Time! Vibrating as configured.");
                startBreakRestTimer();
            }
        }.start();
    }

    private void startBreakRestTimer() {
        Log.d(TAG, "Starting break rest for " + currentBreakRestMillis / 1000 + "s");
        updateNotification("Status: Break Time...");
        if (breakRestTimer != null) breakRestTimer.cancel();
        breakRestTimer = new CountDownTimer(currentBreakRestMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateNotification("Break Rest: " + formatTime(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "Break rest finished. Signaling 'back to work' with interval pattern.");
                triggerVibrationFeedback(VibrationFeedbackType.INTERVAL_BACK_TO_WORK, "Rest over! Back to work (interval pattern).");
                startWorkCycle();
            }
        }.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        if (workTimer != null) workTimer.cancel();
        if (breakRestTimer != null) breakRestTimer.cancel();
        if (vibrator != null) vibrator.cancel();
        stopForeground(true);
        mainThreadHandler.post(() -> Toast.makeText(EyeBlinkerService.this, "Eye Blinker Service Stopped", Toast.LENGTH_SHORT).show());
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Eye Blinker Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Eye Blinker")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_eye_notification) // Ensure this drawable exists
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, createNotification(contentText));
    }

    private String formatTime(long millisUntilFinished) {
        long seconds = (millisUntilFinished / 1000) % 60;
        long minutes = (millisUntilFinished / (1000 * 60)) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
}