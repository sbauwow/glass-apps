package com.glassobd;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * OBD2 HUD for Google Glass Explorer Edition (AOSP 5.1.1).
 *
 * 4 pages: Drive, Engine, Fuel, Diagnostics — swipe left/right to navigate.
 * Exit: [X] button, right-click, swipe down, back/escape
 * Reconnect: tap on Glass touchpad
 * Forget trusted adapters: long-press [X] button
 */
public class MainActivity extends Activity implements ObdManager.Listener {

    private static final int PAGE_COUNT = 4;
    private static final String[] PAGE_NAMES = {"DRIVE", "ENGINE", "FUEL", "DIAG"};

    private ObdManager obd;
    private GestureDetector gestureDetector;
    private PowerManager.WakeLock wakeLock;

    private int currentPage = 0;
    private View[] pages;
    private TextView[] dots;
    private TextView pageLabel;
    private TextView statusText;

    // Page 1: Drive
    private TextView dRpm, dSpeed, dThrottle, dCoolant, dLoad, dVoltage;
    // Page 2: Engine
    private TextView eSpeed, eRpm, eThrottle, eCoolant, eIntake, eTiming, eMaf;
    // Page 3: Fuel
    private TextView fRate, fLevel, fSystem, fPressure, fStft, fLtft;
    // Page 4: Diagnostics
    private TextView gRuntime, gMil, gDtc, gDist, gVoltage;

    private LinearLayout pickerOverlay;

    private static final int COLOR_OK     = Color.WHITE;
    private static final int COLOR_GREEN  = Color.parseColor("#00E676");
    private static final int COLOR_YELLOW = Color.parseColor("#FFEB3B");
    private static final int COLOR_RED    = Color.parseColor("#FF1744");
    private static final int COLOR_DIM    = Color.parseColor("#BDBDBD");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_hud);

        // Shared
        statusText = (TextView) findViewById(R.id.status_text);
        pageLabel  = (TextView) findViewById(R.id.page_label);

        // Pages
        pages = new View[] {
            findViewById(R.id.page_drive),
            findViewById(R.id.page_engine),
            findViewById(R.id.page_fuel),
            findViewById(R.id.page_diag)
        };

        // Page dots
        dots = new TextView[] {
            (TextView) findViewById(R.id.dot0),
            (TextView) findViewById(R.id.dot1),
            (TextView) findViewById(R.id.dot2),
            (TextView) findViewById(R.id.dot3)
        };

        // Drive page views
        dRpm      = (TextView) findViewById(R.id.d_rpm);
        dSpeed    = (TextView) findViewById(R.id.d_speed);
        dThrottle = (TextView) findViewById(R.id.d_throttle);
        dCoolant  = (TextView) findViewById(R.id.d_coolant);
        dLoad     = (TextView) findViewById(R.id.d_load);
        dVoltage  = (TextView) findViewById(R.id.d_voltage);

        // Engine page views
        eSpeed    = (TextView) findViewById(R.id.e_speed);
        eRpm      = (TextView) findViewById(R.id.e_rpm);
        eThrottle = (TextView) findViewById(R.id.e_throttle);
        eCoolant  = (TextView) findViewById(R.id.e_coolant);
        eIntake   = (TextView) findViewById(R.id.e_intake);
        eTiming   = (TextView) findViewById(R.id.e_timing);
        eMaf      = (TextView) findViewById(R.id.e_maf);

        // Fuel page views
        fRate     = (TextView) findViewById(R.id.f_rate);
        fLevel    = (TextView) findViewById(R.id.f_level);
        fSystem   = (TextView) findViewById(R.id.f_system);
        fPressure = (TextView) findViewById(R.id.f_pressure);
        fStft     = (TextView) findViewById(R.id.f_stft);
        fLtft     = (TextView) findViewById(R.id.f_ltft);

        // Diag page views
        gRuntime  = (TextView) findViewById(R.id.g_runtime);
        gMil      = (TextView) findViewById(R.id.g_mil);
        gDtc      = (TextView) findViewById(R.id.g_dtc);
        gDist     = (TextView) findViewById(R.id.g_dist);
        gVoltage  = (TextView) findViewById(R.id.g_voltage);

        // Close button
        View closeBtn = findViewById(R.id.close_btn);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exitApp(); }
        });
        closeBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                obd.clearTrustedDevices();
                statusText.setText("ADAPTERS FORGOTTEN");
                obd.stop();
                obd.start();
                return true;
            }
        });

        // Gesture detector — swipe left/right for pages, down to exit, tap to reconnect
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (pickerOverlay != null && pickerOverlay.getVisibility() == View.VISIBLE) {
                    return false;
                }
                obd.stop();
                obd.start();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                exitApp();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 50) {
                    // Horizontal swipe
                    if (dx < 0) {
                        // Swipe left → next page
                        switchPage(currentPage + 1);
                    } else {
                        // Swipe right → previous page
                        switchPage(currentPage - 1);
                    }
                    return true;
                }

                if (dy > 50 && Math.abs(dy) > Math.abs(dx)) {
                    exitApp();
                    return true;
                }
                return false;
            }
        });

        showPage(0);

        obd = new ObdManager(this);
        obd.setListener(this);
    }

    // ---- Page management ----

    private void switchPage(int page) {
        if (page < 0) page = PAGE_COUNT - 1;
        if (page >= PAGE_COUNT) page = 0;
        showPage(page);
    }

    private void showPage(int page) {
        currentPage = page;
        for (int i = 0; i < PAGE_COUNT; i++) {
            pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            dots[i].setText(i == page ? "●" : "○");
            dots[i].setTextColor(i == page ? COLOR_OK : COLOR_DIM);
        }
        pageLabel.setText(PAGE_NAMES[page]);
    }

    // ---- Lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glassobd:hud");
        wakeLock.acquire();
        obd.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        obd.stop();
    }

    // ---- Touch handling ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            exitApp();
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            exitApp();
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
                obd.stop();
                obd.start();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchPage(currentPage + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                switchPage(currentPage - 1);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                exitApp();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() { exitApp(); }

    private void exitApp() {
        obd.stop();
        finish();
    }

    // ---- OBD callbacks ----

    @Override
    public void onStatusChanged(String status) {
        statusText.setText(status);
    }

    @Override
    public void onDataReceived(ObdData d) {
        hidePicker();
        updateDrivePage(d);
        updateEnginePage(d);
        updateFuelPage(d);
        updateDiagPage(d);
        statusText.setText("LIVE");
    }

    // ---- Page 1: Drive ----

    private void updateDrivePage(ObdData d) {
        dSpeed.setText(String.format("%.0f", d.speedMph));
        dSpeed.setTextColor(speedColor(d.speedMph));

        if (d.rpm > 0) {
            dRpm.setText(String.valueOf(d.rpm));
        } else {
            dRpm.setText("--");
        }
        dRpm.setTextColor(rpmColor(d.rpm));

        dThrottle.setText(String.valueOf(d.throttlePos));

        if (d.coolantTempF != 0 || d.coolantTempC != 0) {
            dCoolant.setText(d.coolantTempF + "°F");
            dCoolant.setTextColor(coolantColor(d.coolantTempF));
        } else {
            dCoolant.setText("--°F");
            dCoolant.setTextColor(COLOR_DIM);
        }

        dLoad.setText(d.engineLoad + "%");

        if (d.voltage > 0) {
            dVoltage.setText(String.format("%.1fV", d.voltage));
        } else {
            dVoltage.setText("--V");
        }
    }

    // ---- Page 2: Engine ----

    private void updateEnginePage(ObdData d) {
        eSpeed.setText(String.format("%.0f", d.speedMph));
        eSpeed.setTextColor(speedColor(d.speedMph));

        if (d.rpm > 0) {
            eRpm.setText(String.valueOf(d.rpm));
        } else {
            eRpm.setText("--");
        }
        eRpm.setTextColor(rpmColor(d.rpm));

        eThrottle.setText(String.valueOf(d.throttlePos));

        if (d.coolantTempF != 0 || d.coolantTempC != 0) {
            eCoolant.setText(d.coolantTempF + "°F");
            eCoolant.setTextColor(coolantColor(d.coolantTempF));
        } else {
            eCoolant.setText("--°F");
            eCoolant.setTextColor(COLOR_DIM);
        }

        if (d.intakeAirTempF != 0 || d.intakeAirTempC != 0) {
            eIntake.setText(d.intakeAirTempF + "°F");
        } else {
            eIntake.setText("--°F");
        }

        if (d.timingAdvance != 0) {
            eTiming.setText(String.format("%.1f°", d.timingAdvance));
        } else {
            eTiming.setText("--°");
        }

        if (d.mafRate > 0) {
            eMaf.setText(String.format("%.1f g/s", d.mafRate));
        } else {
            eMaf.setText("-- g/s");
        }
    }

    // ---- Page 3: Fuel ----

    private void updateFuelPage(ObdData d) {
        if (d.fuelRateLph > 0) {
            fRate.setText(String.format("%.1f", d.fuelRateLph));
        } else {
            fRate.setText("--");
        }

        if (d.fuelLevel > 0) {
            fLevel.setText(String.valueOf(d.fuelLevel));
            fLevel.setTextColor(fuelLevelColor(d.fuelLevel));
        } else {
            fLevel.setText("--");
            fLevel.setTextColor(COLOR_OK);
        }

        if (d.fuelSystem != null) {
            fSystem.setText(d.fuelSystem);
            fSystem.setTextColor("CL".equals(d.fuelSystem) ? COLOR_GREEN : COLOR_YELLOW);
        } else {
            fSystem.setText("--");
            fSystem.setTextColor(COLOR_DIM);
        }

        if (d.fuelPressure > 0) {
            fPressure.setText(d.fuelPressure + " kPa");
        } else {
            fPressure.setText("-- kPa");
        }

        fStft.setText(String.format("ST %+.1f%%", d.stft));
        fStft.setTextColor(trimColor(d.stft));

        fLtft.setText(String.format("LT %+.1f%%", d.ltft));
        fLtft.setTextColor(trimColor(d.ltft));
    }

    // ---- Page 4: Diagnostics ----

    private void updateDiagPage(ObdData d) {
        if (d.runtimeSec > 0) {
            int h = d.runtimeSec / 3600;
            int m = (d.runtimeSec % 3600) / 60;
            if (h > 0) {
                gRuntime.setText(String.format("%d:%02d", h, m));
            } else {
                int s = d.runtimeSec % 60;
                gRuntime.setText(String.format("%d:%02d", m, s));
            }
        } else {
            gRuntime.setText("--:--");
        }

        if (d.milOn) {
            gMil.setText("MIL");
            gMil.setTextColor(COLOR_RED);
        } else {
            gMil.setText("MIL");
            gMil.setTextColor(COLOR_GREEN);
        }

        gDtc.setText(String.valueOf(d.dtcCount));
        gDtc.setTextColor(d.dtcCount > 0 ? COLOR_RED : COLOR_GREEN);

        if (d.distSinceClearedMi > 0) {
            gDist.setText(String.format("%.0f mi", d.distSinceClearedMi));
        } else {
            gDist.setText("-- mi");
        }

        if (d.voltage > 0) {
            gVoltage.setText(String.format("%.1fV", d.voltage));
        } else {
            gVoltage.setText("--V");
        }
    }

    // ---- Device picker ----

    @Override
    public void onDevicesFound(List<ObdManager.FoundDevice> devices) {
        showPicker(devices);
    }

    private void showPicker(List<ObdManager.FoundDevice> devices) {
        hidePicker();

        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);

        pickerOverlay = new LinearLayout(this);
        pickerOverlay.setOrientation(LinearLayout.VERTICAL);
        pickerOverlay.setBackgroundColor(Color.parseColor("#EE000000"));
        pickerOverlay.setGravity(Gravity.CENTER);
        pickerOverlay.setPadding(40, 20, 40, 20);

        TextView title = new TextView(this);
        title.setText("Select OBD adapter (" + devices.size() + " found)");
        title.setTextColor(COLOR_DIM);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        pickerOverlay.addView(title);

        for (final ObdManager.FoundDevice fd : devices) {
            TextView btn = new TextView(this);
            String label = fd.name + "  (" + fd.mac.substring(fd.mac.length() - 5) + ")";
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(20);
            btn.setGravity(Gravity.CENTER);
            btn.setBackgroundColor(Color.parseColor("#333333"));
            btn.setPadding(24, 16, 24, 16);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 8, 0, 8);
            btn.setLayoutParams(lp);

            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hidePicker();
                    obd.connectToDevice(fd);
                }
            });

            pickerOverlay.addView(btn);
        }

        root.addView(pickerOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hidePicker() {
        if (pickerOverlay != null) {
            ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
            root.removeView(pickerOverlay);
            pickerOverlay = null;
        }
    }

    // ---- Color helpers ----

    private int speedColor(double mph) {
        if (mph > 80) return COLOR_RED;
        if (mph > 60) return COLOR_YELLOW;
        return COLOR_OK;
    }

    private int rpmColor(int rpm) {
        if (rpm > 5500) return COLOR_RED;
        if (rpm > 4000) return COLOR_YELLOW;
        return COLOR_OK;
    }

    private int coolantColor(int tempF) {
        if (tempF > 220) return COLOR_RED;
        if (tempF > 200) return COLOR_YELLOW;
        return COLOR_DIM;
    }

    private int fuelLevelColor(int pct) {
        if (pct < 10) return COLOR_RED;
        if (pct < 25) return COLOR_YELLOW;
        return COLOR_OK;
    }

    private int trimColor(double pct) {
        double abs = Math.abs(pct);
        if (abs > 20) return COLOR_RED;
        if (abs > 10) return COLOR_YELLOW;
        return COLOR_DIM;
    }
}
