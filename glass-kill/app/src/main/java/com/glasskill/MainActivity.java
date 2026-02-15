package com.glasskill;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kills all non-essential background processes on Glass.
 * Launches, kills, shows results, auto-exits after 3 seconds.
 *
 * Uses both killBackgroundProcesses (API) and am force-stop (shell)
 * for thorough cleanup.
 */
public class MainActivity extends Activity {

    private static final int AUTO_EXIT_MS = 3000;

    /** Prefixes that should never be killed. */
    private static final String[] PROTECTED_PREFIXES = {
            "android",                          // core runtime
            "com.android.",                      // system apps
            "com.google.android.",               // Google services
            "com.google.glass.",                 // Glass system
            "com.google.process.",               // Google system processes
            "com.glasskill",                     // ourselves
            "com.example.glasslauncher",         // custom launcher
    };

    private TextView statusText;
    private TextView countText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = (TextView) findViewById(R.id.status_text);
        countText = (TextView) findViewById(R.id.count_text);
        logText = (TextView) findViewById(R.id.log_text);

        new KillTask().execute();
    }

    private boolean isProtected(String pkg) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".") || prefix.endsWith(".") && pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private class KillTask extends AsyncTask<Void, String, List<String>> {
        @Override
        protected List<String> doInBackground(Void... params) {
            List<String> killed = new ArrayList<String>();
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            PackageManager pm = getPackageManager();

            // Collect all installed non-system packages
            Set<String> targets = new HashSet<String>();
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (!isProtected(app.packageName)) {
                    targets.add(app.packageName);
                }
            }

            // Also check running processes
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    for (String pkg : proc.pkgList) {
                        if (!isProtected(pkg)) {
                            targets.add(pkg);
                        }
                    }
                }
            }

            for (String pkg : targets) {
                // API kill (background processes)
                am.killBackgroundProcesses(pkg);

                // Shell force-stop (more aggressive — kills foreground too)
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"am", "force-stop", pkg});
                    p.waitFor();
                } catch (Exception e) {
                    // force-stop may fail without root for some packages
                }

                String label = pkg;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    CharSequence name = pm.getApplicationLabel(info);
                    if (name != null) label = name.toString();
                } catch (PackageManager.NameNotFoundException e) {
                    // use package name
                }

                killed.add(label);
                publishProgress(label);
            }

            return killed;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            logText.append("x " + values[0] + "\n");
        }

        @Override
        protected void onPostExecute(List<String> killed) {
            statusText.setText("DONE");
            statusText.setTextColor(getResources().getColor(R.color.green));
            countText.setText(killed.size() + " processes killed");

            // Auto-exit
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, AUTO_EXIT_MS);
        }
    }
}
