package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * recent_apps: the Recent Apps switcher behind a held Back button, for HomeKeyService and InputKeyService.
 *
 * The key helper sees Back first. A short press is handed on as the accessibility Back action; a press held for
 * HOLD_MS opens RecentAppsOverlay instead. While the switcher is up the helper feeds it every D-pad key itself,
 * so it works without taking input focus away from the app underneath. Which apps were opened comes from window
 * changes the helper watches (no usage-stats permission): newest first, at most MAX, kept in preferences.
 */
final class RecentApps {

    static final long HOLD_MS = 500;
    static final int MAX = 12;
    private static final String PREFS = "recent_apps";
    private static final String ORDER = "order";

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean fired;
    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            fired = true;
            RecentAppsOverlay.show(service);
        }
    };

    private static String lastRecorded = "";

    private RecentApps(AccessibilityService service) {
        this.service = service;
    }

    /** The key handler for a service, or null when recent_apps is off or key filtering is. Also asks for window events. */
    static RecentApps attach(AccessibilityService service, Config config) {
        if (!config.recentApps || !config.keyFilter) return null;
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            service.setServiceInfo(info);
        }
        return new RecentApps(service);
    }

    void detach() {
        handler.removeCallbacks(fire);
        RecentAppsOverlay.dismiss();
    }

    /** True when the key is consumed. */
    boolean onKey(KeyEvent event) {
        if (RecentAppsOverlay.isShowing() && !fired) return RecentAppsOverlay.onKey(service, event);
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (fired) {
            // The release of the press that opened the switcher.
            if (up) fired = false;
            return true;
        }
        if (up) {
            handler.removeCallbacks(fire);
            if (!event.isCanceled()) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        } else if (event.getRepeatCount() > 0 || event.isLongPress()) {
            // A firmware that does deliver key repeats: the hold is already confirmed.
            handler.removeCallbacks(fire);
            if (!fired) fire.run();
        } else {
            handler.removeCallbacks(fire);
            handler.postDelayed(fire, HOLD_MS);
        }
        return true;
    }

    /** Notes the app whose window just came to the front. */
    static void onWindow(Context ctx, AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        record(ctx, event.getPackageName().toString());
    }

    static void record(Context ctx, String pkg) {
        if (pkg.isEmpty() || pkg.equals(lastRecorded) || pkg.equals(ctx.getPackageName())
                || pkg.equals("com.android.systemui") || pkg.contains(".inputmethod")) return;
        if (!launchable(ctx, pkg)) return;
        lastRecorded = pkg;
        List<String> order = read(ctx);
        order.remove(pkg);
        order.add(0, pkg);
        while (order.size() > MAX) order.remove(order.size() - 1);
        write(ctx, order);
    }

    static void remove(Context ctx, String pkg) {
        List<String> order = read(ctx);
        order.remove(pkg);
        write(ctx, order);
        if (pkg.equals(lastRecorded)) lastRecorded = "";
    }

    /** Recent packages, newest first, that can still be opened. */
    static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String pkg : read(ctx)) if (launchable(ctx, pkg)) out.add(pkg);
        return out;
    }

    private static boolean launchable(Context ctx, String pkg) {
        return ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null
                || ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private static List<String> read(Context ctx) {
        List<String> order = new ArrayList<>();
        for (String p : prefs(ctx).getString(ORDER, "").split(",")) if (!p.isEmpty()) order.add(p);
        return order;
    }

    private static void write(Context ctx, List<String> order) {
        prefs(ctx).edit().putString(ORDER, TextUtils.join(",", order)).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
