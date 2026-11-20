package com.motonetwork.switcher;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap runs the full n28 workflow (5G Only first,
 *  then band 78 OFF and band 28 ON for SA and NSA). */
public class N28TileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t != null) {
            t.setLabel("n28 Band");
            t.setState(Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, TileLaunchActivity.class);
        i.putExtra("mode", "N28");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 13, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            //noinspection deprecation
            startActivityAndCollapse(i);
        }
    }
}
