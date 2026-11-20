package com.motonetwork.switcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class NetworkWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
            v.setOnClickPendingIntent(R.id.w5g, pi(ctx, "NR", 1));
            v.setOnClickPendingIntent(R.id.w4g, pi(ctx, "LTE", 2));
            v.setOnClickPendingIntent(R.id.wAuto, pi(ctx, "AUTO", 3));
            mgr.updateAppWidget(id, v);
        }
    }

    private PendingIntent pi(Context ctx, String mode, int req) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("mode", mode);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
