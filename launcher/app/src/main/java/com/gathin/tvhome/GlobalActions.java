package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;

import java.lang.ref.WeakReference;

/**
 * Local patch: lets a plain Activity (MainActivity's Settings grid) ask whichever accessibility service happens
 * to be bound (InputKeyService, HomeKeyService — either, neither, or both, depending on the build and the
 * owner's config) to perform a global action, such as the system's own Power dialog. Only an AccessibilityService
 * instance can call performGlobalAction; this is the one thing that needs it outside RemoteKeys' own key
 * handling (RemoteKeys.runAction runs inside the service already).
 */
final class GlobalActions {
    private GlobalActions() { }

    private static WeakReference<AccessibilityService> bound;

    static void bind(AccessibilityService service) {
        bound = new WeakReference<>(service);
    }

    /** Only clears the reference if it is still this exact service — two services can be bound at once. */
    static void unbind(AccessibilityService service) {
        if (bound != null && bound.get() == service) bound = null;
    }

    /** True if a bound service actually performed it; false with nothing bound, for a caller to fall back on. */
    static boolean perform(int globalAction) {
        AccessibilityService service = bound == null ? null : bound.get();
        return service != null && service.performGlobalAction(globalAction);
    }
}
