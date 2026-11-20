package com.motonetwork.switcher;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap resets everything - network mode back to
 *  Auto and bands 28 + 78 turned ON for SA and NSA. */
public class ResetTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t != null) {
            t.setLabel("Reset All");
            t.setState(Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, TileLaunchActivity.class);
        i.putExtra("mode", "RESET");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 14, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            //noinspection deprecation
            startActivityAndCollapse(i);
        }
    }
}
