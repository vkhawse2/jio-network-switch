package com.motonetwork.switcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * v4 auto-tap - safety rework after v3 field log:
 *
 * v3's loop kept running for up to 60s based only on a stored target, so if
 * the user left Phone info it scrolled whatever screen came next (Settings,
 * notifications, other apps). v4 fixes that:
 *  - BEFORE every scroll/click, and on every accessibility event, it verifies
 *    the foreground really is the Phone info screen (or its dropdown popup),
 *    identified by the preferredNetworkType view-id / its label / the
 *    phone-index spinner. If verification fails, it ABORTS immediately:
 *    target cleared, loop stopped, nothing else is ever scrolled or tapped.
 *  - Hard caps: max 8 scrolls looking for the dropdown, max 20 scrolls
 *    inside the dropdown list, 30s total per run.
 *  - The dropdown list itself is scrolled (v3 scrolled the wrong window).
 *  - v5: the list order (user's screenshots) puts "LTE only" near the TOP,
 *    above the NR section the popup opens on - so the popup sweep goes UP
 *    to the top first, then DOWN, checking for the option at every step.
 *  - saved_default is only ever a combo mode (contains "/", never "...only"),
 *    so AUTO can never be corrupted by a forced mode like "NR only".
 */
public class AutoTapService extends AccessibilityService {

    private static final long TARGET_TTL_MS = 45_000;
    private static final int MAX_STAGE_A_SCROLLS = 8;
    private static final int MAX_UP_SCROLLS = 12;
    private static final int MAX_DOWN_SCROLLS = 25;
    private static final int MAX_WAIT_FOR_SCREEN = 8;   // ~5s

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean working = false;
    private boolean spinnerClicked = false;
    private int stageAScrolls = 0;
    private int popupPhase = 0;   // 0 = sweeping UP to the top of the list, 1 = sweeping DOWN
    private int upScrolls = 0;
    private int downScrolls = 0;
    private int downStuck = 0;
    private int waitCount = 0;
    private int lostCount = 0;
    private int reopenCount = 0;
    private int n78Stage = 0;   // see n78Attempt()
    private int n78Wait = 0;
    private int dialIndex = 0;  // next key of *#*#4636#*#* to tap in the dialer
    private int dialRetries = 0;
    private int clearTries = 0;
    private boolean dialCleared = false;
    private static final String SECRET_CODE_KEYS = "*#*#4636#*#*";
    private boolean dumpedThisRun = false;

    // ---------- target ----------
    static boolean isBandMode(String m) { return "N78".equals(m) || "N28".equals(m) || "RESET".equals(m); }

    private String targetMode() {
        SharedPreferences p = getSharedPreferences("auto", MODE_PRIVATE);
        String m = p.getString("target_mode", null);
        long t = p.getLong("target_time", 0);
        if (m == null) return null;
        if (System.currentTimeMillis() - t > TARGET_TTL_MS) return null;
        return m;
    }

    private void clearTarget() {
        getSharedPreferences("auto", MODE_PRIVATE).edit()
                .remove("target_mode").remove("then_mode").apply();
    }

    private void abort(String reason) {
        DiagLog.add(this, "STOPPED: " + reason);
        clearTarget();
        working = false;
        handler.removeCallbacks(attempt);
        Toast.makeText(this, "Auto-tap stopped", Toast.LENGTH_SHORT).show();
    }

    private void succeed(String mode, String optionText) {
        // Reverse chain (RESET): band phase finished first; now switch the
        // network mode (then_mode = AUTO) via Phone info.
        if (isBandMode(mode)) {
            SharedPreferences p = getSharedPreferences("auto", MODE_PRIVATE);
            String then = p.getString("then_mode", null);
            if (then != null && !isBandMode(then)) {
                DiagLog.add(this, "Band phase done ('" + optionText + "') - now switching network to " + then);
                Toast.makeText(this, "All bands ON - now switching network to Auto...", Toast.LENGTH_LONG).show();
                p.edit().putString("target_mode", then)
                        .putLong("target_time", System.currentTimeMillis())
                        .remove("then_mode").apply();
                spinnerClicked = false;
                stageAScrolls = 0; popupPhase = 0;
                upScrolls = 0; downScrolls = 0; downStuck = 0;
                waitCount = 0; lostCount = 0; reopenCount = 0;
                dumpedThisRun = false;
                MainActivity.openRadioInfo(this, then);
                handler.postDelayed(attempt, 600);
                return;
            }
        }
        // Chained workflow (n78): phase 1 was setting NR only; when it lands,
        // switch the target to the band phase and open the Testing route.
        if (!isBandMode(mode)) {
            SharedPreferences p = getSharedPreferences("auto", MODE_PRIVATE);
            String then = p.getString("then_mode", null);
            if (isBandMode(then)) {
                DiagLog.add(this, "Phase 1 done ('" + optionText + "' set) - starting phase 2: band steps for " + then);
                Toast.makeText(this, "RESET".equals(then)
                        ? "Network back to Auto - now restoring all bands..."
                        : "5G Only set - now the band steps...", Toast.LENGTH_LONG).show();
                p.edit().putString("target_mode", then)
                        .putLong("target_time", System.currentTimeMillis())
                        .remove("then_mode").apply();
                n78Stage = 0; n78Wait = 0;
                dialIndex = 0; dialRetries = 0; clearTries = 0; dialCleared = false;
                n78OpIndex = 0; n78OpStep = 0; n78ConfirmTries = 0; n78DumpedOp = -1;
                MainActivity.openTesting(this);
                handler.postDelayed(attempt, 600);
                return;
            }
        }
        DiagLog.add(this, "SUCCESS - tapped option '" + optionText + "' for mode " + mode);
        Toast.makeText(this, "Done: " + MainActivity.titleFor(mode) + " selected", Toast.LENGTH_LONG).show();
        clearTarget();
        working = false;
        handler.removeCallbacks(attempt);
        // Every workflow ends on the home screen (user request).
        DiagLog.add(this, "Workflow complete - going to home screen");
        handler.postDelayed(() -> {
            boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
            DiagLog.add(this, "Home action -> " + ok);
        }, 900);
    }

    // ---------- lifecycle / events ----------
    @Override
    protected void onServiceConnected() {
        DiagLog.add(this, "Auto-tap service connected (v23)");
        healSavedDefault();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(attempt);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        boolean allowed = pkg.equals("com.android.phone") || pkg.equals("com.android.settings");
        // SystemUI fires events constantly (status bar, clock, toasts, the
        // dropdown popup itself) and our own app fires them too (its toast /
        // activity underneath). Neither means the user left Phone info -
        // treating them as "user left" in v4/v5 caused random instant aborts,
        // which is why it worked only sometimes. Ignore both completely.
        boolean ignored = pkg.equals("com.android.systemui")
                || pkg.equals(getPackageName()) || pkg.equals("android")
                || pkg.contains("inputmethod");
        if (ignored) return;
        if (!working && isBandMode(targetMode())) {
            // The debugging screens may live in a telephony package we don't
            // know yet. Starting the loop is safe: n78Attempt only ever taps
            // exact texts from that flow and aborts if they never appear.
            working = true;
            n78Stage = 0;
            n78Wait = 0;
            dialIndex = 0;
        dialRetries = 0; clearTries = 0; dialCleared = false;
            dialRetries = 0; clearTries = 0; dialCleared = false;
            DiagLog.add(this, "Run started, target=" + targetMode() + " (event from " + pkg + ")");
            handler.postDelayed(attempt, 200);
            return;
        }
        if (working && !allowed && !isBandMode(targetMode())) {
            // User moved to another real app mid-run: stop at once, touch nothing there.
            abort("user left Phone info (now in " + pkg + ")");
            return;
        }
        if (!allowed || working || targetMode() == null) return;
        working = true;
        spinnerClicked = false;
        stageAScrolls = 0;
        popupPhase = 0;
        upScrolls = 0;
        downScrolls = 0;
        downStuck = 0;
        waitCount = 0;
        lostCount = 0;
        reopenCount = 0;
        n78Stage = 0;
        n78Wait = 0;
        dialIndex = 0;
        dialRetries = 0; clearTries = 0; dialCleared = false;
        dumpedThisRun = false;
        DiagLog.add(this, "Run started, target=" + targetMode());
        handler.postDelayed(attempt, 200);
    }

    @Override
    public void onInterrupt() { }

    // ---------- main loop: verifies the screen before EVERY action ----------
    private final Runnable attempt = new Runnable() {
        @Override public void run() {
            String mode = targetMode();
            if (mode == null) {
                if (working) abort("timed out");
                return;
            }
            List<AccessibilityNodeInfo> roots = collectRoots();
            if (roots.isEmpty()) { reschedule(600); return; }

            if (isBandMode(mode)) { n78Attempt(roots); return; }

            boolean onRadioInfo = isRadioInfoScreen(roots);
            AccessibilityNodeInfo popupRoot = findPopupRoot(roots);

            if (!spinnerClicked) {
                // ------- Stage A: must be on Phone info, never scroll anything else
                if (!onRadioInfo) {
                    waitCount++;
                    if (waitCount > MAX_WAIT_FOR_SCREEN) {
                        abort("Phone info screen not found - user may have moved away");
                        return;
                    }
                    reschedule(300);   // wait quietly, do NOT scroll
                    return;
                }
                waitCount = 0;
                if (!dumpedThisRun) { dumpedThisRun = true; dumpSpinners(roots); }

                AccessibilityNodeInfo spinner = findSpinner(roots);
                if (spinner != null) {
                    String val = spinnerValue(spinner);
                    DiagLog.add(AutoTapService.this, "Network spinner found, current value='" + val + "'");
                    maybeSaveDefault(val);
                    boolean ok = clickNode(spinner);
                    if (!ok) {
                        Rect r = new Rect(); spinner.getBoundsInScreen(r);
                        ok = tapAt(r.centerX(), r.centerY());
                    }
                    DiagLog.add(AutoTapService.this, "Spinner click -> " + ok);
                    if (ok) { spinnerClicked = true; popupPhase = 0; upScrolls = 0; downScrolls = 0; downStuck = 0; lostCount = 0; reschedule(450); return; }
                }
                AccessibilityNodeInfo phoneInfo = findText(roots, "Phone information", true);
                if (phoneInfo != null && findText(roots, "preferred network type", false) == null) {
                    clickNode(phoneInfo);
                    DiagLog.add(AutoTapService.this, "Tapped 'Phone information' list entry");
                    reschedule(600);
                    return;
                }
                AccessibilityNodeInfo label = findText(roots, "preferred network type", false);
                if (label != null) {
                    Rect r = new Rect(); label.getBoundsInScreen(r);
                    int y = r.bottom + 90;
                    DiagLog.add(AutoTapService.this, "Label visible, no spinner node - tap below label");
                    if (tapAt(r.centerX(), y)) { spinnerClicked = true; popupPhase = 0; upScrolls = 0; downScrolls = 0; downStuck = 0; lostCount = 0; reschedule(450); return; }
                }
                if (stageAScrolls >= MAX_STAGE_A_SCROLLS) {
                    abort("dropdown not found after " + MAX_STAGE_A_SCROLLS + " scrolls on Phone info");
                    return;
                }
                stageAScrolls++;
                scrollRadioInfo(roots);
                reschedule(220);
            } else {
                // ------- Stage B: dropdown popup must be open
                if (popupRoot != null) {
                    lostCount = 0;
                    // The list on this phone (from the user's screenshots) runs:
                    // GSM/WCDMA... -> LTE only (near the top) -> TDSCDMA... ->
                    // NR only -> NR/LTE (current, near the bottom) -> NR/LTE combos.
                    // The popup opens centred on the CURRENT value, so "LTE only"
                    // is usually ABOVE the visible part. Sweep UP to the top first,
                    // checking at every step, then sweep DOWN through the whole list.
                    AccessibilityNodeInfo option = findOption(roots, mode);
                    if (option != null) { clickNode(option); succeed(mode, textOf(option)); return; }
                    if (upScrolls == 2 && popupPhase == 0) dumpTexts(roots, "Popup texts");
                    if (popupPhase == 0) {
                        if (upScrolls >= MAX_UP_SCROLLS) {
                            popupPhase = 1;
                            DiagLog.add(AutoTapService.this, "Reached top of dropdown list, now sweeping down");
                        } else {
                            upScrolls++;
                            if (!scrollPopup(popupRoot, false)) {
                                popupPhase = 1;
                                DiagLog.add(AutoTapService.this, "Already at top of dropdown list, sweeping down");
                            }
                        }
                        reschedule(220);
                        return;
                    }
                    // phase 1: sweeping down
                    if (downScrolls >= MAX_DOWN_SCROLLS || downStuck >= 3) {
                        abort("option not found after sweeping the whole dropdown list");
                        return;
                    }
                    downScrolls++;
                    if (!scrollPopup(popupRoot, true)) downStuck++; else downStuck = 0;
                    reschedule(220);
                } else if (onRadioInfo) {
                    // Popup closed without a selection
                    if (reopenCount >= 1) { abort("dropdown closed without selecting"); return; }
                    reopenCount++;
                    DiagLog.add(AutoTapService.this, "Dropdown closed - reopening once");
                    spinnerClicked = false;
                    stageAScrolls = 0;
                    reschedule(500);
                } else {
                    // Neither popup nor Phone info visible in this snapshot.
                    // That can be a transient frame while the popup animates,
                    // so give it a few chances before concluding the user left.
                    lostCount++;
                    if (lostCount >= 4) { abort("user left the dropdown"); return; }
                    reschedule(400);
                    return;
                }
            }
        }
    };

    private void reschedule(long ms) {
        if (!working) return;
        handler.postDelayed(attempt, ms);
    }

    // ---------- screen verification ----------
    private boolean isRadioInfoScreen(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String id = n.getViewIdResourceName();
                if (id != null && id.toLowerCase().contains("preferrednetworktype")) return true;
                String t = textOf(n);
                if (t.toLowerCase().contains("set preferred network type")) return true;
                if (t.toLowerCase().startsWith("select phone index")) return true;
            }
        }
        return false;
    }

    /** The dropdown popup = a root holding at least 3 network-mode list items. */
    private AccessibilityNodeInfo findPopupRoot(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            int count = 0;
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                if (isNetworkText(t) && !t.contains(":") && t.length() <= 45) count++;
            }
            if (count >= 3) return root;
        }
        return null;
    }

    // ---------- saved default (combo modes only) ----------
    private void healSavedDefault() {
        SharedPreferences p = getSharedPreferences("auto", MODE_PRIVATE);
        String s = p.getString("saved_default", null);
        if (s != null && s.toLowerCase().contains("only")) {
            p.edit().remove("saved_default").apply();
            DiagLog.add(this, "Removed corrupted saved default '" + s + "'");
        }
    }

    private void maybeSaveDefault(String val) {
        if (val == null || val.isEmpty()) return;
        if (!val.contains("/") || val.toLowerCase().contains("only")) return; // forced modes are never a default
        getSharedPreferences("auto", MODE_PRIVATE).edit().putString("saved_default", val).apply();
        DiagLog.add(this, "Saved as default for AUTO: '" + val + "'");
    }

    // ---------- tree helpers ----------
    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (Exception ignored) { }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !roots.contains(active)) roots.add(active);
        return roots;
    }

    private List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
        }
        return out;
    }

    private String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    private boolean isNetworkText(String s) {
        if (s == null || s.isEmpty()) return false;
        String u = s.toUpperCase();
        boolean radio = u.contains("NR") || u.contains("LTE") || u.contains("GSM")
                || u.contains("WCDMA") || u.contains("CDMA") || u.contains("EVDO")
                || u.contains("TDSCDMA") || u.contains("UMTS");
        return radio && (s.contains("/") || u.endsWith("ONLY") || u.contains("(PRL)") || u.contains("AUTO"));
    }

    private boolean isPhoneIndexText(String s) {
        return s != null && s.trim().toLowerCase().startsWith("phone ");
    }

    private String spinnerValue(AccessibilityNodeInfo spinner) {
        for (AccessibilityNodeInfo c : allNodes(spinner)) {
            String t = textOf(c);
            if (!t.isEmpty() && c != spinner) return t;
        }
        return textOf(spinner);
    }

    private boolean isSpinner(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        return c != null && c.toString().contains("Spinner");
    }

    private AccessibilityNodeInfo findSpinner(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String id = n.getViewIdResourceName();
                if (id != null && id.toLowerCase().contains("preferrednetworktype")) return n;
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                if (isSpinner(n)) {
                    String v = spinnerValue(n);
                    if (isNetworkText(v) && !isPhoneIndexText(v)) return n;
                }
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                if (textOf(n).toLowerCase().contains("preferred network type")) {
                    AccessibilityNodeInfo p = n;
                    for (int up = 0; up < 4 && p != null; up++) {
                        for (AccessibilityNodeInfo c : allNodes(p)) {
                            if (isSpinner(c)) {
                                String v = spinnerValue(c);
                                if (isNetworkText(v) && !isPhoneIndexText(v)) return c;
                            }
                        }
                        p = p.getParent();
                    }
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findText(List<AccessibilityNodeInfo> roots, String needle, boolean startsWith) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                boolean hit = startsWith ? t.startsWith(needle)
                        : t.toLowerCase().contains(needle.toLowerCase());
                if (hit) return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findOption(List<AccessibilityNodeInfo> roots, String mode) {
        // Pass 1: exact / priority matches
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                String u = t.toUpperCase();
                if ("NR".equals(mode) && u.contains("NR ONLY")) return n;
                if ("LTE".equals(mode) && u.contains("LTE ONLY")) return n;
                if ("AUTO".equals(mode) && t.equals("NR/LTE")) return n;
            }
        }
        if (!"AUTO".equals(mode)) return null;
        // Pass 2: the saved real default, then best-scoring combo
        String saved = getSharedPreferences("auto", MODE_PRIVATE).getString("saved_default", null);
        AccessibilityNodeInfo fallback = null;
        int best = -999;
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                String u = t.toUpperCase();
                if (saved != null && t.equals(saved) && !u.contains("ONLY")) return n;
                if (isNetworkText(t) && u.contains("NR") && u.contains("LTE") && !u.contains("ONLY")) {
                    int score = 0;
                    if (u.contains("GSM")) score += 2;
                    if (u.contains("WCDMA")) score += 2;
                    if (u.contains("CDMA")) score -= 1;
                    if (u.contains("EVDO")) score -= 1;
                    if (u.contains("TDSCDMA")) score -= 1;
                    if (score > best) { best = score; fallback = n; }
                }
            }
        }
        return fallback;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            cur = cur.getParent();
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** Scroll ONLY the Phone info page (its own scrollable area). */
    private void scrollRadioInfo(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            boolean isInfo = false;
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String id = n.getViewIdResourceName();
                if (id != null && id.toLowerCase().contains("preferrednetworktype")) { isInfo = true; break; }
                if (textOf(n).toLowerCase().startsWith("select phone index")) { isInfo = true; break; }
            }
            if (!isInfo) continue;
            for (AccessibilityNodeInfo n : allNodes(root)) {
                if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return;
            }
        }
    }

    /** Scroll ONLY the dropdown list window, in the given direction. */
    private boolean scrollPopup(AccessibilityNodeInfo popupRoot, boolean forward) {
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                             : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        for (AccessibilityNodeInfo n : allNodes(popupRoot)) {
            if (n.isScrollable() && n.performAction(action)) return true;
        }
        Rect r = new Rect(); popupRoot.getBoundsInScreen(r);
        if (r.width() <= 0) return false;
        if (forward) {
            swipeAt(r.centerX(), (int) (r.top + r.height() * 0.75f), (int) (r.top + r.height() * 0.25f));
        } else {
            swipeAt(r.centerX(), (int) (r.top + r.height() * 0.25f), (int) (r.top + r.height() * 0.75f));
        }
        return true;
    }

    // ---------- gestures ----------
    private boolean tapAt(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) { return false; }
    }

    private void swipeAt(int x, int y1, int y2) {
        try {
            Path path = new Path();
            path.moveTo(x, y1);
            path.lineTo(x, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 300)).build();
            dispatchGesture(g, null, null);
        } catch (Exception ignored) { }
    }

    // ---------- n78 band flow ----------
    // Testing > "5G/LTE Debugging Information" > overflow menu (top right) >
    // "NR options:" > "Enable/Disable NR SA band" > enter 78 > ENABLE,
    // then the same again for the NSA band. Every tap is gated on the exact
    // texts from the user's screenshots of this flow.
    private void n78Advance(int next) {
        n78Stage = next;
        n78Wait = 0;
    }

    private void n78WaitOrAbort(String what) {
        n78Wait++;
        if (n78Wait > 22) { abort("n78: gave up waiting for " + what + " (stage " + n78Stage + ")"); return; }
        reschedule(300);
    }

    /** Find a dialpad key in the stock dialer by its label/description. */
    private AccessibilityNodeInfo findDialKey(List<AccessibilityNodeInfo> roots, char key) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int minY = (int) (dm.heightPixels * 0.30f); // dialpad lives in the lower part
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                CharSequence d = n.getContentDescription();
                String desc = d == null ? "" : d.toString().toLowerCase();
                boolean match;
                if (key == '*') match = t.equals("*") || desc.contains("star");
                else if (key == '#') match = t.equals("#") || desc.contains("pound") || desc.contains("hash");
                else match = t.equals(String.valueOf(key)) || desc.startsWith(String.valueOf(key));
                if (!match) continue;
                Rect r = new Rect(); n.getBoundsInScreen(r);
                if (r.centerY() > minY && r.width() > 0) return n;
            }
        }
        return null;
    }


    private static final String DIAL_LAYOUT = "123456789*0#";

    /** Center point of a dialpad key: exact node if found, otherwise
     *  extrapolated from any keys that ARE visible (dialpads are always a
     *  3x4 grid in the same order). Null if no key is visible at all. */
    private android.graphics.Point dialKeyPoint(List<AccessibilityNodeInfo> roots, char key) {
        java.util.Map<Character, android.graphics.Point> found = new java.util.HashMap<>();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int minY = (int) (dm.heightPixels * 0.30f);
        for (char c : DIAL_LAYOUT.toCharArray()) {
            AccessibilityNodeInfo n = findDialKey(roots, c);
            if (n != null) {
                Rect r = new Rect(); n.getBoundsInScreen(r);
                found.put(c, new android.graphics.Point(r.centerX(), r.centerY()));
            }
        }
        if (found.containsKey(key)) return found.get(key);
        if (found.isEmpty()) return null;
        int ki = DIAL_LAYOUT.indexOf(key);
        int kCol = ki % 3, kRow = ki / 3;
        // cell size from neighbours if possible, else screen-based estimate
        float cellW = dm.widthPixels / 3f;
        float cellH = cellW * 0.82f;
        for (java.util.Map.Entry<Character, android.graphics.Point> e : found.entrySet()) {
            int ai = DIAL_LAYOUT.indexOf(e.getKey());
            int aCol = ai % 3, aRow = ai / 3;
            for (java.util.Map.Entry<Character, android.graphics.Point> e2 : found.entrySet()) {
                int bi = DIAL_LAYOUT.indexOf(e2.getKey());
                if (bi % 3 != aCol && bi / 3 == aRow && Math.abs(bi % 3 - aCol) == 1)
                    cellW = Math.abs(e2.getValue().x - e.getValue().x);
                if (bi / 3 != aRow && bi % 3 == aCol && Math.abs(bi / 3 - aRow) == 1)
                    cellH = Math.abs(e2.getValue().y - e.getValue().y);
            }
            int x = (int) (e.getValue().x + (kCol - aCol) * cellW);
            int y = (int) (e.getValue().y + (kRow - aRow) * cellH);
            x = Math.max(10, Math.min(dm.widthPixels - 10, x));
            y = Math.max(minY, Math.min(dm.heightPixels - 10, y));
            return new android.graphics.Point(x, y);
        }
        return null;
    }

    /** Text currently in the dialer's number field, or null if not found. */
    private String dialerFieldText(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                CharSequence c = n.getClassName();
                if (c != null && c.toString().contains("EditText")) {
                    String t = textOf(n);
                    if (!t.isEmpty()) return t;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByDesc(List<AccessibilityNodeInfo> roots, String needle) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                CharSequence d = n.getContentDescription();
                if (d != null && d.toString().toLowerCase().contains(needle)) return n;
            }
        }
        return null;
    }

    private boolean onDebuggingScreen(List<AccessibilityNodeInfo> roots) {
        return findText(roots, "NR Config:", false) != null
                || findText(roots, "All Enabled Bands:", false) != null
                || findText(roots, "ENDC State:", false) != null;
    }

    private boolean tapOverflowMenu(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                CharSequence d = n.getContentDescription();
                if (d != null && d.toString().toLowerCase().contains("more options")) {
                    return clickNode(n);
                }
            }
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return tapAt((int) (dm.widthPixels * 0.94f), (int) (dm.heightPixels * 0.07f));
    }

    // The band workflow as a queue of operations. Per the correct procedure:
    // after NR only is set, DISABLE band 28 then ENABLE band 78, for SA and
    // then for NSA (n28 makes the phone camp on slow 700 MHz 5G).
    private static final String[][] N78_OPS = {
        {"SA", "28", "DISABLE"},
        {"SA", "78", "ENABLE"},
        {"NSA", "28", "DISABLE"},
        {"NSA", "78", "ENABLE"},
    };
    // Mirror image for the n28 button: 78 off, 28 on.
    private static final String[][] N28_OPS = {
        {"SA", "78", "DISABLE"},
        {"SA", "28", "ENABLE"},
        {"NSA", "78", "DISABLE"},
        {"NSA", "28", "ENABLE"},
    };
    // Reset: every band back ON, both modes.
    private static final String[][] RESET_OPS = {
        {"SA", "28", "ENABLE"},
        {"SA", "78", "ENABLE"},
        {"NSA", "28", "ENABLE"},
        {"NSA", "78", "ENABLE"},
    };
    private String[][] currentOps() {
        if ("N28".equals(targetMode())) return N28_OPS;
        if ("RESET".equals(targetMode())) return RESET_OPS;
        return N78_OPS;
    }
    private String bandSummary() {
        if ("N28".equals(targetMode())) return "n28 on, n78 off (SA + NSA)";
        if ("RESET".equals(targetMode())) return "all bands on (28 + 78, SA + NSA)";
        return "n78 on, n28 off (SA + NSA)";
    }
    private int n78OpIndex = 0;
    private int n78OpStep = 0; // 0 = open menu, 1 = open band item, 2 = dialog, 3 = confirm dialog closed
    private int n78ConfirmTries = 0;
    private int n78DumpedOp = -1;

    private void bandOpAttempt(List<AccessibilityNodeInfo> roots) {
        String[][] ops = currentOps();
        if (n78OpIndex >= ops.length) { succeed(targetMode(), bandSummary()); return; }
        String kind = ops[n78OpIndex][0];
        String band = ops[n78OpIndex][1];
        String action = ops[n78OpIndex][2];

        // Confirm step: we tapped the action button; the op only counts when
        // the dialog has actually closed. Retry the tap twice, then move on
        // (a later op succeeding still beats aborting the whole run).
        if (n78OpStep == 3) {
            if (findText(roots, "Enter NR " + kind + " band number", false) == null) {
                DiagLog.add(this, "n78: confirmed " + action + " " + kind + " band " + band);
                n78OpIndex++; n78OpStep = 0; n78Wait = 0; n78ConfirmTries = 0;
                reschedule(500); return;
            }
            n78ConfirmTries++;
            if (n78ConfirmTries <= 2) {
                AccessibilityNodeInfo btn = findActionButton(roots, action);
                if (btn != null) {
                    clickNode(btn);
                    DiagLog.add(this, "n78: dialog still open - tapped " + action + " again (try " + n78ConfirmTries + ")");
                }
                reschedule(800); return;
            }
            DiagLog.add(this, "n78: could NOT confirm " + action + " " + kind + " band " + band + " - moving to next op anyway");
            n78OpIndex++; n78OpStep = 0; n78Wait = 0; n78ConfirmTries = 0;
            reschedule(500); return;
        }

        // The dialog can appear while we still think we are in a menu step.
        if (handleBandDialog2(roots, kind, band, action)) return;
        switch (n78OpStep) {
            case 0: {
                AccessibilityNodeInfo nrOpt = findText(roots, "NR options:", true);
                if (nrOpt != null) {
                    clickNode(nrOpt);
                    DiagLog.add(this, "n78: tapped 'NR options:' (op: " + action + " " + kind + " " + band + ")");
                    n78OpStep = 1; n78Wait = 0; reschedule(600); return;
                }
                if (onDebuggingScreen(roots)) {
                    tapOverflowMenu(roots);
                    DiagLog.add(this, "n78: tapped overflow menu");
                    n78Wait = 0; reschedule(600); return;
                }
                n78WaitOrAbort("debugging screen / NR options menu");
                return;
            }
            case 1: {
                String want = "Enable/Disable NR " + kind + " band";
                AccessibilityNodeInfo item = findText(roots, want, true);
                if (item != null) {
                    clickNode(item);
                    DiagLog.add(this, "n78: tapped '" + want + "'");
                    n78OpStep = 2; n78Wait = 0; reschedule(700); return;
                }
                AccessibilityNodeInfo nrOpt = findText(roots, "NR options:", true);
                if (nrOpt != null) { clickNode(nrOpt); n78Wait = 0; reschedule(600); return; }
                n78WaitOrAbort("'" + want + "' menu item");
                return;
            }
            default:
                n78WaitOrAbort("band dialog for " + kind + " " + band);
                return;
        }
    }

    /** Find a dialog action button without assuming its exact label:
     *  DISABLE may be labelled Disable/REMOVE/DELETE/OFF, ENABLE may be
     *  Enable/ADD/ON/OK depending on build. Case-insensitive. */
    private AccessibilityNodeInfo findActionButton(List<AccessibilityNodeInfo> roots, String action) {
        String[] synonyms = "DISABLE".equals(action)
                ? new String[]{"DISABLE", "REMOVE", "DELETE", "TURN OFF", "OFF"}
                : new String[]{"ENABLE", "ADD", "TURN ON", "ON", "OK"};
        for (String syn : synonyms) {
            for (AccessibilityNodeInfo root : roots) {
                for (AccessibilityNodeInfo n : allNodes(root)) {
                    if (textOf(n).equalsIgnoreCase(syn)) return n;
                }
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n).toUpperCase();
                if (!t.isEmpty() && t.contains(synonyms[0])) return n;
            }
        }
        return null;
    }

    /** If the "Enter NR <kind> band number" dialog is open: type the band,
     *  tap ENABLE or DISABLE (per the current op), advance the queue. */
    private boolean handleBandDialog2(List<AccessibilityNodeInfo> roots, String kind, String band, String action) {
        AccessibilityNodeInfo title = findText(roots, "Enter NR " + kind + " band number", false);
        if (title == null) return false;
        // Once per op, record exactly what the dialog shows - if a label is
        // different on this build, the log now contains the real words.
        if (n78DumpedOp != n78OpIndex) {
            n78DumpedOp = n78OpIndex;
            StringBuilder dump = new StringBuilder();
            for (AccessibilityNodeInfo root : roots) {
                for (AccessibilityNodeInfo n : allNodes(root)) {
                    String t = textOf(n);
                    if (!t.isEmpty() && t.length() <= 45) {
                        if (dump.length() > 0) dump.append(" | ");
                        dump.append(t);
                    }
                }
            }
            String d = dump.toString();
            if (d.length() > 500) d = d.substring(0, 500);
            DiagLog.add(this, "n78: dialog contents for " + kind + " op: " + d);
        }
        AccessibilityNodeInfo field = null;
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                CharSequence c = n.getClassName();
                if (field == null && c != null && c.toString().contains("EditText")) field = n;
            }
        }
        if (field == null) { DiagLog.add(this, "n78: dialog open but no input field found"); return true; }
        if (!textOf(field).equals(band)) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, band);
            boolean set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            DiagLog.add(this, "n78: typed " + band + " into " + kind + " band field (setText=" + set + ")");
        }
        AccessibilityNodeInfo actionBtn = findActionButton(roots, action);
        if (actionBtn != null) {
            DiagLog.add(this, "n78: tapping button '" + textOf(actionBtn) + "' for " + action + " " + kind + " band " + band);
            clickNode(actionBtn);
            n78OpStep = 3;
            n78ConfirmTries = 0;
            n78Wait = 0;
            reschedule(800);
        } else {
            DiagLog.add(this, "n78: no " + action + " button found in " + kind + " dialog (see dialog contents above)");
            n78WaitOrAbort(action + " button in " + kind + " dialog");
        }
        return true;
    }

    private void n78Attempt(List<AccessibilityNodeInfo> roots) {
        switch (n78Stage) {
            case 0: { // get to the debugging screen
                if (onDebuggingScreen(roots)) {
                    DiagLog.add(this, "n78: on 5G/LTE Debugging screen");
                    n78Advance(1); reschedule(300); return;
                }
                AccessibilityNodeInfo item = findText(roots, "5G/LTE Debugging Information", true);
                if (item != null) {
                    clickNode(item);
                    DiagLog.add(this, "n78: tapped '5G/LTE Debugging Information'");
                    n78Advance(1); reschedule(700); return;
                }
                // Dialer route: the Testing activity is not exported on this
                // phone, but typing *#*#4636#*#* in the stock dialer opens it.
                // Fail-safe order: clear leftover digits -> tap keys by label
                // -> tap keys by grid position -> retry the whole code once.
                if (dialIndex < SECRET_CODE_KEYS.length()) {
                    if (!dialCleared) {
                        String existing = dialerFieldText(roots);
                        AccessibilityNodeInfo del = findByDesc(roots, "delete");
                        if (del == null) del = findByDesc(roots, "backspace");
                        if (existing != null && del != null && clearTries < 20) {
                            clickNode(del);
                            clearTries++;
                            DiagLog.add(this, "n78: clearing leftover digits from dialer ('" + existing + "')");
                            reschedule(150);
                            return;
                        }
                        dialCleared = true;
                    }
                    char key = SECRET_CODE_KEYS.charAt(dialIndex);
                    AccessibilityNodeInfo keyNode = findDialKey(roots, key);
                    if (keyNode != null) {
                        clickNode(keyNode);
                        DiagLog.add(this, "n78: dialed key '" + key + "' (" + (dialIndex + 1) + "/" + SECRET_CODE_KEYS.length() + ")");
                        dialIndex++;
                        n78Wait = 0;
                        reschedule(dialIndex >= SECRET_CODE_KEYS.length() ? 1000 : 220);
                        return;
                    }
                    android.graphics.Point pt = dialKeyPoint(roots, key);
                    if (pt != null) {
                        tapAt(pt.x, pt.y);
                        DiagLog.add(this, "n78: grid-tapped key '" + key + "' at " + pt.x + "," + pt.y + " (" + (dialIndex + 1) + "/" + SECRET_CODE_KEYS.length() + ")");
                        dialIndex++;
                        n78Wait = 0;
                        reschedule(dialIndex >= SECRET_CODE_KEYS.length() ? 1000 : 220);
                        return;
                    }
                    AccessibilityNodeInfo fab = findByDesc(roots, "keypad");
                    if (fab == null) fab = findByDesc(roots, "dialpad");
                    if (fab == null) fab = findByDesc(roots, "dial pad");
                    if (fab == null) fab = findByDesc(roots, "key pad");
                    if (fab != null) {
                        clickNode(fab);
                        DiagLog.add(this, "n78: opened dialpad");
                        n78Wait = 0;
                        reschedule(600);
                        return;
                    }
                    n78WaitOrAbort("dialpad (open the Phone app keypad)");
                    return;
                }
                // Code fully typed: wait for Testing; retry the code once if
                // it does not appear (e.g. a tap was missed the first time).
                n78Wait++;
                if (n78Wait == 11 && dialRetries < 1) {
                    dialRetries++;
                    dialIndex = 0;
                    dialCleared = false;
                    clearTries = 0;
                    n78Wait = 0;
                    DiagLog.add(this, "n78: Testing did not appear - retrying the code once");
                    reschedule(400);
                    return;
                }
                if (n78Wait > 24) { abort("n78: gave up waiting for Testing list / debugging screen after dialing code (stage 0)"); return; }
                reschedule(300);
                return;
            }
            default:
                bandOpAttempt(roots);
                return;
        }
    }

    // ---------- diagnostics ----------
    private void dumpSpinners(List<AccessibilityNodeInfo> roots) {
        StringBuilder sb = new StringBuilder("Spinners on screen:");
        boolean any = false;
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                if (isSpinner(n)) {
                    any = true;
                    Rect r = new Rect(); n.getBoundsInScreen(r);
                    sb.append(" [id=").append(n.getViewIdResourceName())
                      .append(" value='").append(spinnerValue(n)).append("']");
                }
            }
        }
        if (!any) sb.append(" (none)");
        DiagLog.add(this, sb.toString());
    }

    private void dumpTexts(List<AccessibilityNodeInfo> roots, String title) {
        StringBuilder sb = new StringBuilder(title).append(":");
        int count = 0;
        for (AccessibilityNodeInfo root : roots) {
            for (AccessibilityNodeInfo n : allNodes(root)) {
                String t = textOf(n);
                if (!t.isEmpty() && count < 40) { sb.append(" | ").append(t); count++; }
            }
        }
        DiagLog.add(this, sb.toString());
    }
}
