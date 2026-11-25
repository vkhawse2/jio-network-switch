package com.motonetwork.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;



public class MainActivity extends Activity {

    static String labelFor(String mode) {
        if ("NR".equals(mode)) return "NR only";
        if ("LTE".equals(mode)) return "LTE only";
        if ("N78".equals(mode)) return "78 (SA + NSA)";
        if ("N28".equals(mode)) return "28 (SA + NSA)";
        if ("RESET".equals(mode)) return "reset all";
        return "NR/LTE";
    }

    static String titleFor(String mode) {
        if ("NR".equals(mode)) return "5G Only";
        if ("LTE".equals(mode)) return "4G Only";
        if ("N78".equals(mode)) return "n78 Band ON";
        if ("N28".equals(mode)) return "n28 Band ON";
        if ("RESET".equals(mode)) return "Reset All";
        return "Auto (Default)";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bind(R.id.btn5g, "NR");
        bind(R.id.btn4g, "LTE");
        bind(R.id.btnAuto, "AUTO");
        bind(R.id.btnN78, "N78");
        bind(R.id.btnN28, "N28");
        bind(R.id.btnReset, "RESET");

        Button tilesBtn = findViewById(R.id.btnAddTiles);
        tilesBtn.setOnClickListener(v -> requestAddTiles());
        Button enable = findViewById(R.id.btnEnableAuto);
        enable.setOnClickListener(v -> showEnableInstructions());

        Button stop = findViewById(R.id.btnStopAuto);
        stop.setOnClickListener(v -> {
            getSharedPreferences("auto", MODE_PRIVATE).edit().remove("target_mode").remove("then_mode").apply();
            DiagLog.add(this, "STOP pressed by user - pending auto-tap cancelled");
            Toast.makeText(this, "Auto-tap cancelled. It will not scroll or tap anything.", Toast.LENGTH_LONG).show();
            refreshAutoStatus();
        });

        Button upiOff = findViewById(R.id.btnUpiOff);
        upiOff.setOnClickListener(v -> showUpiOffInstructions());

        Button menuBtn = findViewById(R.id.btnMenu);
        menuBtn.setOnClickListener(v -> showMenu(v));

        String mode = getIntent() != null ? getIntent().getStringExtra("mode") : null;
        if (mode != null) askAndOpen(mode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutoStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String mode = intent != null ? intent.getStringExtra("mode") : null;
        if (mode != null) askAndOpen(mode);
    }

    // ---------- auto-tap (accessibility) ----------
    static boolean isAutoTapEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName me = new ComponentName(ctx, AutoTapService.class);
            return enabled.contains(me.flattenToString())
                    || enabled.contains(ctx.getPackageName() + "/" + AutoTapService.class.getName())
                    || enabled.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshAutoStatus() {
        TextView tv = findViewById(R.id.autoStatus);
        Button enable = findViewById(R.id.btnEnableAuto);
        if (tv == null) return;
        if (isAutoTapEnabled(this)) {
            tv.setText("Ready — just tap a button below. After the switch, Auto-Tap turns itself OFF so UPI / banking apps keep working.");
            enable.setVisibility(View.GONE);
        } else {
            tv.setText("Auto-Tap is OFF (UPI-safe). To switch, tap the orange button and turn it ON — it turns itself OFF again after the switch.");
            enable.setVisibility(View.VISIBLE);
        }
    }

    // ---------- 3-dot menu: manual + log ----------

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "How to use");
        menu.getMenu().add(0, 5, 1, "Privacy policy");
        menu.getMenu().add(0, 2, 2, "View log");
        menu.getMenu().add(0, 3, 3, "Copy log");
        menu.getMenu().add(0, 4, 4, "Clear log");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showHowTo(); return true;
                case 5: showPrivacyPolicy(); return true;
                case 2: showLogDialog(); return true;
                case 3: copyLog(); return true;
                case 4:
                    DiagLog.clear(this);
                    Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show();
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyLog() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", DiagLog.get(this)));
        Toast.makeText(this, "Log copied - paste it wherever you need it", Toast.LENGTH_LONG).show();
    }

    private void showLogDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Log")
            .setMessage(DiagLog.get(this))
            .setPositiveButton("Copy", (d, w) -> copyLog())
            .setNegativeButton("Close", null)
            .show();
    }

    private void showHowTo() {
        new AlertDialog.Builder(this)
            .setTitle("How to use")
            .setMessage(
                "SETUP (quick, before each switch)\n" +
                "Tap the orange setup button, then: Installed apps > \"5G / 4G Auto-Tap\" > switch ON > Allow. Then every button works with a single tap.\n\n" +
                "UPI / BANKING APPS\n" +
                "Banks block UPI while a screen-reading / auto-tap service is ON. So this app turns Auto-Tap OFF by itself as soon as a switch finishes — UPI works normally the rest of the time. You just switch Auto-Tap ON again before your next switch. If a UPI app complains while Auto-Tap is ON, use the \"TURN OFF AUTO-TAP — FOR UPI / BANKING\" button on the main screen.\n\n" +
                "5G ONLY\n" +
                "Uses only 5G - fastest. If the 5G signal is lost you may get no signal until you switch back. Calls need VoNR.\n\n" +
                "4G ONLY\n" +
                "Uses only 4G / LTE.\n\n" +
                "AUTO (NORMAL)\n" +
                "The phone's normal mode - it picks 5G / 4G / 3G / 2G by itself.\n\n" +
                "TURN ON n78 BAND\n" +
                "First sets 5G Only, then turns band 28 OFF and band 78 ON (for SA and NSA). Strong, fast 5G in cities (Jio / Airtel).\n\n" +
                "TURN ON n28 BAND\n" +
                "First sets 5G Only, then turns band 78 OFF and band 28 ON (for SA and NSA). 5G that reaches further - better indoors and in villages.\n\n" +
                "RESET EVERYTHING\n" +
                "Back to normal: first turns bands 28 and 78 ON (SA and NSA), then sets the network to Auto.\n\n" +
                "ADD SHORTCUT TILES\n" +
                "Puts the same actions in your Quick Settings panel - pull down twice from the top of the screen and tap a tile.\n\n" +
                "STOP\n" +
                "Cancels a running action right away.\n\n" +
                "GOOD TO KNOW\n" +
                "- Every action ends back on your home screen when it finishes.\n" +
                "- Works on SIM 1.\n" +
                "- No computer, no root, nothing else needed.\n" +
                "- Something not working? Menu (top right) > Copy log, and send it to the person who gave you this app.")
            .setPositiveButton("Got it", null)
            .show();
    }

    /** UPI-safe (v26): only the service itself can switch itself off, and it
     *  now does that automatically after every run. This button covers the
     *  in-between case (service ON, no run): it takes the user straight to
     *  the accessibility toggle so UPI works right away. */
    private void showUpiOffInstructions() {
        if (!isAutoTapEnabled(this)) {
            Toast.makeText(this, "Auto-Tap is already OFF — UPI / banking apps should work now. If one still complains, close and reopen it.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Turn OFF Auto-Tap for UPI")
            .setMessage("UPI and banking apps stop working while any app that can read the screen and tap is switched on — that's a safety rule from the banks, not a fault in your phone.\n\n" +
                "Good news: from now on Auto-Tap turns itself OFF automatically after every switch finishes, so you normally don't need this button.\n\n" +
                "If a UPI app is complaining right now, tap \"Open settings\" and switch \"5G / 4G Auto-Tap\" OFF there, then close and reopen your UPI app.")
            .setPositiveButton("Open settings >", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showEnableInstructions() {
        new AlertDialog.Builder(this)
            .setTitle("One-time setup — please read")
            .setMessage("This app uses an Accessibility Service called \"5G / 4G Auto-Tap\".\n\n" +
                "WHAT IT DOES\n" +
                "Only after you tap a button in this app (for example 5G Only), the service looks at the screen content of Android's Phone Information and Testing / Debugging screens and taps the option you chose for you — the network mode or the band. It works only on those screens, only right after your tap, and stops as soon as you leave them or press STOP.\n\n" +
                "WHAT DATA IT ACCESSES\n" +
                "It reads the text and buttons shown on those two system screens so it can find the right option to tap. It does NOT collect, store, share or send any personal data. It has no internet access, and nothing it sees ever leaves your phone. The only thing saved on the phone is a short log of the steps it took, which you can view, copy or clear from the menu.\n\n" +
                "Android asks you to switch this on yourself — no app is allowed to do it for you. If you agree, tap \"Agree and start setup\":\n\n1. On the next screen, tap \"Installed apps\"\n2. Tap \"5G / 4G Auto-Tap\"\n3. Switch it ON and tap Allow\n\nThen come back here and tap any button. Nothing else is needed: no computer, no root.\n\nUPI NOTE: UPI / banking apps block phones while a service like this is ON, so Auto-Tap turns itself OFF automatically as soon as each switch finishes. That means you switch it ON before a switch, and it is OFF again right after — UPI keeps working in between.")
            .setPositiveButton("Agree and start setup >", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showPrivacyPolicy() {
        new AlertDialog.Builder(this)
            .setTitle("Privacy policy")
            .setMessage("5G 4G Switch — Privacy Policy\n\n" +
                "This app does not collect, store, share or transmit any personal data.\n\n" +
                "- No internet: the app has no internet permission and never connects to any server.\n" +
                "- No accounts, no ads, no analytics, no trackers.\n" +
                "- Accessibility service: after you tap a button, it reads the text and buttons on Android's Phone Information and Testing / Debugging screens only, so it can tap the network option you chose. That screen content is used in the moment, on your phone only, and is never saved or sent anywhere.\n" +
                "- Log: the app keeps a short on-device log of the steps it took (times and actions, no personal data). You can view, copy or clear it any time from the menu (top right). It is deleted when you clear it or uninstall the app.\n" +
                "- Data deletion: because nothing is collected, there is nothing to delete on any server. Uninstalling the app removes all its on-device data.\n\n" +
                "Questions: contact the developer via the app's store listing page.")
            .setPositiveButton("Close", null)
            .show();
    }

    static void setPendingTarget(Context ctx, String mode) {
        SharedPreferences p = ctx.getSharedPreferences("auto", Context.MODE_PRIVATE);
        p.edit().putString("target_mode", mode)
                 .putLong("target_time", System.currentTimeMillis())
                 .apply();
        DiagLog.add(ctx, "Mode tapped: " + mode + " (auto-tap " + (isAutoTapEnabled(ctx) ? "ON" : "OFF") + ")");
    }

    static void clearPendingTarget(Context ctx) {
        ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).edit()
                .remove("target_mode").remove("then_mode").apply();
    }

    /** Start a two-phase run: first set `first` (e.g. NR only), and when it
     *  lands, the service automatically continues with `then` (e.g. N78). */
    static void setPendingTargetChain(Context ctx, String first, String then) {
        ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).edit()
                .putString("target_mode", first)
                .putLong("target_time", System.currentTimeMillis())
                .putString("then_mode", then)
                .apply();
        DiagLog.add(ctx, "Mode tapped: " + then + " (full workflow: " + first + " first, then " + then + ")");
    }

    // ---------- main flow ----------
    private void bind(int id, final String mode) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> askAndOpen(mode));
    }

    private void askAndOpen(final String mode) {
        if (isAutoTapEnabled(this)) {
            // Fully automatic: store target, open the right screen, service taps the rest
            if ("RESET".equals(mode)) {
                // Reset workflow order: bands 28 + 78 ON first, then Auto.
                setPendingTargetChain(this, "RESET", "AUTO");
                Toast.makeText(this, "Resetting: all bands ON, then network to Auto...", Toast.LENGTH_SHORT).show();
                openTesting(this);
            } else if ("N78".equals(mode) || "N28".equals(mode)) {
                // Band workflows chain: 5G Only first, then the band steps.
                setPendingTargetChain(this, "NR", mode);
                Toast.makeText(this, "Setting 5G Only first, then the band steps...", Toast.LENGTH_SHORT).show();
                openRadioInfo(this, "NR");
            } else {
                setPendingTarget(this, mode);
                Toast.makeText(this, "Switching... just a few seconds", Toast.LENGTH_SHORT).show();
                openRadioInfo(this, mode);
            }
            return;
        }
        if ("RESET".equals(mode)) {
            new AlertDialog.Builder(this)
                .setTitle(titleFor(mode))
                .setMessage("This puts everything back to normal, in this order:\n\n1. Bands 28 and 78 both turned ON (SA and NSA)\n2. Network mode back to Auto\n\nWith the one-time setup, the app does all of this for you in one tap. Do the setup first: 30 seconds, only once.")
                .setPositiveButton("Set up (one-time)", (d, w) -> showEnableInstructions())
                .setNeutralButton("Open screen anyway", (d, w) -> openTesting(this))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        if ("N78".equals(mode) || "N28".equals(mode)) {
            String offBand = "N78".equals(mode) ? "28" : "78";
            String onBand = "N78".equals(mode) ? "78" : "28";
            new AlertDialog.Builder(this)
                .setTitle(titleFor(mode))
                .setMessage("The correct order matters:\n\n1. Set the network to 5G Only\n2. In the Testing > Debugging screen, turn OFF band " + offBand + "\n3. Turn ON band " + onBand + " (for SA and NSA)\n\nWith the one-time setup, the app does all of this for you — just tap the button. Do the setup first: 30 seconds, only once.")
                .setPositiveButton("Set up (one-time)", (d, w) -> showEnableInstructions())
                .setNeutralButton("Open screen anyway", (d, w) -> openRadioInfo(this, "NR"))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        String msg = "This app can do it for you in one tap — it just needs a one-time setup first (30 seconds).\n\nTap SET UP, switch on \"5G / 4G Auto-Tap\", come back, and tap the button again. That's all.";
        if ("NR".equals(mode)) {
            msg += "\n\nNote: in 5G Only, if the 5G signal is lost you may get no signal. Tap AUTO to go back to normal.";
        }
        new AlertDialog.Builder(this)
            .setTitle(titleFor(mode))
            .setMessage(msg)
            .setPositiveButton("Set up (one-time)", (d, w) -> showEnableInstructions())
            .setNeutralButton("I'll tap myself", (d, w) -> openRadioInfo(this, mode))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Try every known way to open Android's hidden Phone Information (RadioInfo)
     *  screen directly - no dialer code, no ADB, no root. */
    static void openRadioInfo(Context ctx, String mode) {
        if (!isAutoTapEnabled(ctx)) {
            Toast.makeText(ctx, "Select: " + labelFor(mode), Toast.LENGTH_LONG).show();
        }
        String[][] targets = {
            // Proven on the Edge 40 from the v3 log - this one opens Phone info
            {"com.android.phone", "com.android.phone.settings.RadioInfo"},
            {"com.android.phone", "com.android.phone.RadioInfo"},
            {"com.android.settings", "com.android.settings.RadioInfo"},
            {"com.android.settings", "com.android.settings.TestingSettings"},
            {"com.android.phone", "com.android.phone.settings.TestingSettings"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(t[0], t[1]));
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                DiagLog.add(ctx, "Opened Phone info via " + t[0] + "/" + t[1]);
                return;
            } catch (Exception e) {
                DiagLog.add(ctx, "Open failed " + t[1] + ": " + e.getClass().getSimpleName());
            }
        }
        try {
            Intent i = new Intent("android.provider.Telephony.SECRET_CODE",
                    Uri.parse("android_secret_code://4636"));
            ctx.sendBroadcast(i);
            Toast.makeText(ctx, "Opened via secret code - tap Phone information", Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) { }
        try {
            Intent i = new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:*%23*%234636%23*%23"));
            ctx.startActivity(i);
            Toast.makeText(ctx, "Press the last # if the menu does not open automatically", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Your firmware blocked the hidden menu. Use Settings > Network > SIM > Preferred network type instead.", Toast.LENGTH_LONG).show();
        }
    }

    /** Ask Android to pin the n78 + Testing tiles into the QS panel (API 33+).
     *  Called via reflection: the method exists on-device (Android 13+) but
     *  is missing from the SDK stub jar we compile against. */
    private void requestAddTiles() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Swipe down twice, tap the pencil, and add the 'n78 Band' and 'Testing Menu' tiles", Toast.LENGTH_LONG).show();
            return;
        }
        requestAddOne(new ComponentName(this, N78TileService.class), "n78 Band", () ->
                requestAddOne(new ComponentName(this, N28TileService.class), "n28 Band", () ->
                        requestAddOne(new ComponentName(this, TestingTileService.class), "Testing Menu", () ->
                                requestAddOne(new ComponentName(this, ResetTileService.class), "Reset All", null))));
    }

    private void requestAddOne(ComponentName comp, String label, Runnable next) {
        try {
            java.lang.reflect.Method m = TileService.class.getMethod("requestAddTileService",
                    Context.class, ComponentName.class, CharSequence.class, Icon.class,
                    java.util.concurrent.Executor.class, java.util.function.Consumer.class);
            java.util.function.Consumer<Boolean> cb = ok -> {
                DiagLog.add(this, label + " tile add result: " + ok);
                if (next != null) next.run();
            };
            DiagLog.add(this, "Requesting QS tile add: " + label);
            m.invoke(null, this, comp, label,
                    Icon.createWithResource(this, R.drawable.ic_signal), getMainExecutor(), cb);
        } catch (Throwable t) {
            DiagLog.add(this, "requestAddTileService unavailable (" + t.getClass().getSimpleName()
                    + ") - add the tiles manually via the QS pencil");
            Toast.makeText(this, "Swipe down twice, tap the pencil, and add the 'n78 Band' and 'Testing Menu' tiles", Toast.LENGTH_LONG).show();
        }
    }

    /** Open Android's Testing screen (list with "5G/LTE Debugging Information").
     *  The Testing activity is not exported on this phone (direct launch =
     *  SecurityException), so: try on-device discovered activities first,
     *  then open the dialer and let auto-tap type *#*#4636#*#*. */
    static void openTesting(Context ctx) {
        // 0) A Debugging activity that opened directly on a previous run is
        // cached - try it first, it skips everything else.
        try {
            String cached = ctx.getSharedPreferences("auto", Context.MODE_PRIVATE)
                    .getString("debug_component", null);
            if (cached != null && cached.contains("/")) {
                String[] parts = cached.split("/", 2);
                Intent ci = new Intent(Intent.ACTION_MAIN);
                ci.setComponent(new ComponentName(parts[0], parts[1]));
                ci.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(ci);
                DiagLog.add(ctx, "Opened Debugging directly (cached): " + cached);
                return;
            }
        } catch (Exception e) {
            DiagLog.add(ctx, "Cached Debugging open failed - rescanning");
            ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).edit().remove("debug_component").apply();
        }
        java.util.List<String[]> candidates = new java.util.ArrayList<>();

        // 1) Runtime discovery on the actual device
        String[] pkgs = {"com.android.settings", "com.android.phone",
                "com.mediatek.telephony", "com.mediatek.phone",
                "com.google.android.dialer", "com.android.dialer"};
        java.util.List<String[]> debugDirect = new java.util.ArrayList<>();
        for (String pkg : pkgs) {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES);
                if (pi.activities == null) { DiagLog.add(ctx, "Scan " + pkg + ": no activity list"); continue; }
                int hits = 0;
                for (android.content.pm.ActivityInfo a : pi.activities) {
                    String n = a.name;
                    String low = n.toLowerCase();
                    if (low.contains("testing") || low.contains("debug")
                            || low.contains("telephonyinfo")) {
                        hits++;
                        DiagLog.add(ctx, "Found in " + pkg + ": " + n);
                        if (low.contains("debug")) debugDirect.add(new String[]{pkg, n});
                        else candidates.add(new String[]{pkg, n});
                    }
                }
                DiagLog.add(ctx, "Scan " + pkg + ": " + pi.activities.length + " activities, " + hits + " matches");
            } catch (Exception e) {
                DiagLog.add(ctx, "Scan " + pkg + " failed: " + e.getClass().getSimpleName());
            }
        }

        // 2) Wide scan: every package on the phone, but only telephony-type
        // packages are trusted for a direct launch (others could be random
        // debug screens of unrelated apps).
        try {
            java.util.List<android.content.pm.PackageInfo> allPkgs = ctx.getPackageManager()
                    .getInstalledPackages(android.content.pm.PackageManager.GET_ACTIVITIES);
            int wideHits = 0;
            for (android.content.pm.PackageInfo pi : allPkgs) {
                String pkg = pi.packageName;
                String plow = pkg.toLowerCase();
                if (!(plow.contains("phone") || plow.contains("telephony") || plow.contains("settings")
                        || plow.contains("mediatek") || plow.contains("motorola") || plow.contains("engineer"))) continue;
                if (pi.activities == null) continue;
                for (android.content.pm.ActivityInfo a : pi.activities) {
                    String low = a.name.toLowerCase();
                    if (!low.contains("debug")) continue;
                    boolean seen = false;
                    for (String[] d : debugDirect) if (d[0].equals(pkg) && d[1].equals(a.name)) { seen = true; break; }
                    if (seen) continue;
                    wideHits++;
                    DiagLog.add(ctx, "Wide scan found in " + pkg + ": " + a.name);
                    debugDirect.add(new String[]{pkg, a.name});
                }
            }
            DiagLog.add(ctx, "Wide scan: " + allPkgs.size() + " packages, " + wideHits + " extra debug matches");
        } catch (Exception e) {
            DiagLog.add(ctx, "Wide scan failed: " + e.getClass().getSimpleName());
        }

        // Direct debugging screens first (skips the Testing list entirely),
        // then discovered Testing screens. On this phone the discovered
        // TestingSettingsActivity exists but is not exported (SecurityException),
        // so the reliable route is the dialer: open it and let the auto-tap
        // service type *#*#4636#*#* on its keypad, exactly like a finger would.
        java.util.List<String[]> all = new java.util.ArrayList<>();
        all.addAll(debugDirect);
        all.addAll(candidates);

        for (int idx = 0; idx < all.size(); idx++) {
            String[] t = all.get(idx);
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(t[0], t[1]));
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                DiagLog.add(ctx, "Opened Testing/Debug via " + t[0] + "/" + t[1]);
                if (idx < debugDirect.size()) {
                    ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).edit()
                            .putString("debug_component", t[0] + "/" + t[1]).apply();
                    DiagLog.add(ctx, "Cached as direct Debugging shortcut for next time");
                }
                return;
            } catch (Exception e) {
                DiagLog.add(ctx, "Open failed " + t[1] + ": " + e.getClass().getSimpleName());
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_DIAL);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            DiagLog.add(ctx, "Opened dialer - auto-tap will type *#*#4636#*#*");
        } catch (Exception e) {
            DiagLog.add(ctx, "Open dialer failed: " + e.getClass().getSimpleName());
            Toast.makeText(ctx, "Could not open the dialer. Please paste the log to Muse.", Toast.LENGTH_LONG).show();
        }
    }
}
