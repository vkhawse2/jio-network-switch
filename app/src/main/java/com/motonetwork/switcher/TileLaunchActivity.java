package com.motonetwork.switcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible trampoline launched by the Quick Settings tiles. Runs the
 *  requested flow (open the Testing menu, optionally with the n78 auto-tap
 *  run armed) and finishes immediately - nothing appears on screen except
 *  the target system screen itself. */
public class TileLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent().getStringExtra("mode");
        DiagLog.add(this, "Tile tapped, mode=" + mode);
        if ("RESET".equals(mode)) {
            // Reset order: bands 28 + 78 ON first, then network to Auto.
            MainActivity.setPendingTargetChain(this, "RESET", "AUTO");
            Toast.makeText(this, "Resetting: all bands ON, then network to Auto...", Toast.LENGTH_SHORT).show();
            MainActivity.openTesting(this);
        } else if ("N78".equals(mode) || "N28".equals(mode)) {
            // Full workflow: 5G Only first, then the band steps (service chains it).
            MainActivity.setPendingTargetChain(this, "NR", mode);
            Toast.makeText(this, "Setting 5G Only first, then the band steps...", Toast.LENGTH_SHORT).show();
            MainActivity.openRadioInfo(this, "NR");
        } else if ("TESTING".equals(mode)) {
            // Just the menu, no auto-tap run.
            MainActivity.clearPendingTarget(this);
            MainActivity.openTesting(this);
        } else if (mode != null) {
            MainActivity.setPendingTarget(this, mode);
            MainActivity.openRadioInfo(this, mode);
        }
        finish();
    }
}
