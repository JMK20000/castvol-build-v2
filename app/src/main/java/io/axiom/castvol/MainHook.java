package io.axiom.castvol;

import android.media.AudioManager;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "[CastVolUnlock]";

    // Remote/cast stream IDs across Android versions
    // STREAM_REMOTE_CALL = 100, plugin streams >= 100
    static final Set<Integer> CAST_STREAMS = new HashSet<>(Arrays.asList(100, 101, 102));

    static final String[] CONTROLLER_CLASSES = {
        "com.android.systemui.volume.VolumeDialogControllerImpl",
        "com.android.systemui.miui.volume.MiuiVolumeDialogController",
        "com.android.systemui.volume.panel.component.mediaoutput.VolumeDialogController",
    };

    static final String[] DIALOG_CLASSES = {
        "com.android.systemui.volume.VolumeDialogImpl",
        "com.android.systemui.miui.volume.MiuiVolumeDialog",
        "com.android.systemui.miui.volume.VolumePanelDialog",
        "com.android.systemui.volume.panel.ui.dialog.VolumePanelDialogFragment",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!lp.packageName.equals("com.android.systemui") &&
            !lp.packageName.equals("com.miui.systemui")) return;

        log("Injected into: " + lp.packageName);
        hookVolumeController(lp.classLoader);
        hookVolumeDialog(lp.classLoader);
        hookAudioManagerBridge(lp.classLoader);
    }

    // ─── CONTROLLER LAYER ────────────────────────────────────────────────────

    void hookVolumeController(ClassLoader cl) {
        for (String cname : CONTROLLER_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cname, cl);
            if (cls == null) continue;
            log("Controller found: " + cname);

            for (Method m : getAllMethods(cls)) {
                String mn = m.getName();

                // shouldBeVisibleH(int stream, ...) — primary gate for row display
                if ((mn.contains("Visible") || mn.contains("visible") || mn.equals("shouldBeVisibleH"))
                        && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0] == int.class) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            int stream = (int) p.args[0];
                            if (isCastStream(stream)) {
                                p.setResult(true);
                                log("shouldBeVisible: forced true for stream=" + stream);
                            }
                        }
                    });
                }

                // onRemoteVolumeChangedW — triggers cast volume UI event
                if (mn.contains("RemoteVolume") || mn.contains("remoteVolume")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("RemoteVolume trigger: " + mn + " args=" + Arrays.toString(p.args));
                            // Optionally: force-show panel here by injecting show call
                        }
                    });
                }

                // dispatchCallbackW / notifyCallbacks — broadcast state to dialog
                if (mn.equals("dispatchCallbackW") || mn.contains("notifyCallback")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            injectRemoteStateToCallbacks(p.thisObject, cls);
                        }
                    });
                }
            }
        }
    }

    void injectRemoteStateToCallbacks(Object ctrl, Class<?> cls) {
        try {
            for (Field f : getAllFields(cls)) {
                f.setAccessible(true);
                String fn = f.getName().toLowerCase();
                if (f.getType() != boolean.class) continue;
                if (fn.contains("remote") || fn.contains("cast") || fn.contains("hasRemote")) {
                    f.set(ctrl, true);
                    log("Controller field patched: " + f.getName());
                }
            }
        } catch (Exception e) {
            log("injectRemoteState err: " + e);
        }
    }

    // ─── DIALOG LAYER ────────────────────────────────────────────────────────

    void hookVolumeDialog(ClassLoader cl) {
        for (String cname : DIALOG_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cname, cl);
            if (cls == null) continue;
            log("Dialog found: " + cname);

            for (Method m : getAllMethods(cls)) {
                String mn = m.getName();

                // updateRowsH — iterates rows, sets visibility per stream state
                if (mn.equals("updateRowsH") || mn.contains("updateRows") || mn.equals("bindRow")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            scanAndRevealRows(p.thisObject);
                        }
                    });
                }

                // addRow — intercept row creation, unflag removal of cast rows
                if (mn.equals("addRow") || mn.equals("addVolumeRow")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            // Allow all stream rows through, don't early-exit cast rows
                            log("addRow: args=" + Arrays.toString(p.args));
                        }
                    });
                }

                // onStateChanged / onInit — initial render pass
                if (mn.equals("onStateChanged") || mn.equals("initRows") || mn.equals("onCreateDialog")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            scanAndRevealRows(p.thisObject);
                        }
                    });
                }
            }
        }
    }

    void scanAndRevealRows(Object dialog) {
        try {
            for (Field f : getAllFields(dialog.getClass())) {
                if (!f.getName().toLowerCase().contains("row")) continue;
                f.setAccessible(true);
                Object val = f.get(dialog);

                if (val instanceof Collection) {
                    for (Object row : (Collection<?>) val) {
                        forceShowRow(row);
                    }
                } else if (val != null && val.getClass().isArray()) {
                    for (Object row : (Object[]) val) {
                        if (row != null) forceShowRow(row);
                    }
                }
            }
        } catch (Exception e) {
            log("scanRows err: " + e);
        }
    }

    void forceShowRow(Object row) {
        try {
            Integer stream = null;
            View rowView = null;

            for (Field f : getAllFields(row.getClass())) {
                f.setAccessible(true);
                String fn = f.getName();

                if ((fn.equals("stream") || fn.equals("mStream")) && f.getType() == int.class) {
                    stream = (Integer) f.get(row);
                }
                if (View.class.isAssignableFrom(f.getType())
                        && (fn.equals("view") || fn.equals("mView") || fn.equals("mRootView"))) {
                    rowView = (View) f.get(row);
                }
            }

            if (stream == null || !isCastStream(stream)) return;
            log("Revealing row: stream=" + stream);

            if (rowView != null) {
                final View v = rowView;
                v.post(() -> v.setVisibility(View.VISIBLE));
            }

            // Patch boolean fields on the row itself
            for (Field f : getAllFields(row.getClass())) {
                if (f.getType() != boolean.class) continue;
                String fn = f.getName().toLowerCase();
                if (fn.contains("show") || fn.contains("visible") || fn.contains("active")) {
                    f.setAccessible(true);
                    f.set(row, true);
                }
            }

        } catch (Exception e) {
            log("forceShowRow err: " + e);
        }
    }

    // ─── AUDIOMANAGER BRIDGE ─────────────────────────────────────────────────

    void hookAudioManagerBridge(ClassLoader cl) {
        // Hook getStreamVolume to never throw for remote stream IDs
        try {
            Class<?> am = XposedHelpers.findClassIfExists("android.media.AudioManager", cl);
            if (am == null) return;

            XposedHelpers.findAndHookMethod(am, "getStreamVolume", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    int s = (int) p.args[0];
                    if (isCastStream(s)) {
                        // Prevent invalid stream exception for cast streams
                        // Return last known remote volume or default 5
                        p.setResult(5);
                        log("getStreamVolume: intercepted cast stream=" + s + " -> 5");
                    }
                }
            });

        } catch (Exception e) {
            log("AudioManager bridge err: " + e);
        }
    }

    // ─── UTILS ───────────────────────────────────────────────────────────────

    boolean isCastStream(int stream) {
        return CAST_STREAMS.contains(stream) || stream >= 100;
    }

    List<Method> getAllMethods(Class<?> cls) {
        List<Method> methods = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            methods.addAll(Arrays.asList(c.getDeclaredMethods()));
            c = c.getSuperclass();
        }
        return methods;
    }

    List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
        android.util.Log.d(TAG, msg);
    }
}
