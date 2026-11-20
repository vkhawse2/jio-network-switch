package com.motonetwork.switcher;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap opens Android's Testing menu directly
 *  (the *#*#4636#*#* screen) - just the menu, no auto-tap run. */
public class TestingTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t != null) {
            t.setLabel("Testing Menu");
            t.setState(Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, TileLaunchActivity.class);
        i.putExtra("mode", "TESTING");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 12, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            //noinspection deprecation
            startActivityAndCollapse(i);
        }
    }
}
