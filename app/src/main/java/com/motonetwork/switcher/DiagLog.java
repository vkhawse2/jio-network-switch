package com.motonetwork.switcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DiagLog {
    public static synchronized void add(Context ctx, String msg) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE);
            String old = p.getString("log", "");
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String next = old + time + "  " + msg + "\n";
            if (next.length() > 6000) next = next.substring(next.length() - 6000);
            p.edit().putString("log", next).apply();
        } catch (Exception ignored) { }
    }

    public static String get(Context ctx) {
        String s = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("log", "");
        return s.isEmpty() ? "(no log yet - tap 5G ONLY or 4G ONLY once, then come back here)" : s;
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().remove("log").apply();
    }
}
