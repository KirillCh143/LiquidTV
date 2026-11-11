package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Local patch: "About" page's backup of the launcher's own settings, as one small JSON file. It holds the launcher
 * settings (every choice made in Launcher settings), and the owner's tile order and library lists (the plain text
 * files in the launcher's private folder). Wallpapers and themes are not in it. Saved to the TV's Downloads folder
 * (through MediaStore, which needs no storage permission) and as a copy in the launcher's own folder, so a restore
 * works from either.
 */
final class SettingsBackup {
    static final String FILE_NAME = "liquidtv-launcher-settings.json";
    private static final String[] FILES = {"order.txt", "library_names.txt", "library_apps.txt",
            "library_home_removed.txt", "library_hidden.txt"};

    private SettingsBackup() {}

    /** Writes the backup; returns where it went (a path to show), or throws. */
    static String save(Context context) throws Exception {
        byte[] data = export(context).toString(2).getBytes("UTF-8");
        File dir = Config.dir(context);
        if (dir != null) {
            File copy = new File(dir, "backup/" + FILE_NAME);
            copy.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(copy)) {
                out.write(data);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            Uri existing = findInDownloads(resolver);
            Uri target = existing;
            if (target == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            }
            if (target == null) throw new java.io.IOException("no Downloads");
            try (OutputStream out = resolver.openOutputStream(target, "wt")) {
                out.write(data);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + FILE_NAME;
        }
        // Before Android 10 only the launcher's own folder can be written without a permission.
        if (dir == null) throw new java.io.IOException("no folder");
        return new File(dir, "backup/" + FILE_NAME).getAbsolutePath();
    }

    /** Puts the saved settings back; false when there is no backup to restore. */
    static boolean restore(Context context) throws Exception {
        String json = readFromDownloads(context);
        if (json == null) {
            File dir = Config.dir(context);
            File copy = dir == null ? null : new File(dir, "backup/" + FILE_NAME);
            if (copy != null && copy.isFile()) json = read(new FileInputStream(copy));
        }
        if (json == null) return false;
        apply(context, new JSONObject(json));
        return true;
    }

    /** Back to defaults: every choice made in Launcher settings is forgotten. */
    static void reset(Context context) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static JSONObject export(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", 1);
        root.put("app", "liquidOS");
        try {
            root.put("version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            // the version is only informative
        }
        root.put("created", System.currentTimeMillis());
        JSONObject prefs = new JSONObject();
        SharedPreferences sp = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (Config.SETTINGS_REVISION_KEY.equals(e.getKey())) continue;
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) { item.put("t", "bool"); item.put("v", v); }
            else if (v instanceof Integer) { item.put("t", "int"); item.put("v", v); }
            else if (v instanceof Long) { item.put("t", "long"); item.put("v", v); }
            else if (v instanceof Float) { item.put("t", "float"); item.put("v", (double) (Float) v); }
            else if (v instanceof String) { item.put("t", "string"); item.put("v", v); }
            else if (v instanceof Set) { item.put("t", "set"); item.put("v", new JSONArray((Set<?>) v)); }
            else continue;
            prefs.put(e.getKey(), item);
        }
        root.put("preferences", prefs);
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            File f = new File(context.getFilesDir(), name);
            if (f.isFile()) files.put(name, read(new FileInputStream(f)));
        }
        root.put("files", files);
        return root;
    }

    private static void apply(Context context, JSONObject root) throws Exception {
        String app = root.optString("app");
        if (!"liquidOS".equals(app) && !"LiquidTV".equals(app)) throw new IllegalArgumentException("not a liquidOS backup");
        SharedPreferences sp = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = sp.edit().clear();
        JSONObject prefs = root.optJSONObject("preferences");
        if (prefs != null) {
            for (Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject item = prefs.getJSONObject(key);
                switch (item.getString("t")) {
                    case "bool": edit.putBoolean(key, item.getBoolean("v")); break;
                    case "int": edit.putInt(key, item.getInt("v")); break;
                    case "long": edit.putLong(key, item.getLong("v")); break;
                    case "float": edit.putFloat(key, (float) item.getDouble("v")); break;
                    case "string": edit.putString(key, item.getString("v")); break;
                    case "set": {
                        JSONArray array = item.getJSONArray("v");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < array.length(); i++) set.add(array.getString(i));
                        edit.putStringSet(key, set);
                        break;
                    }
                    default:
                }
            }
        }
        edit.commit();
        JSONObject files = root.optJSONObject("files");
        for (String name : FILES) {
            File f = new File(context.getFilesDir(), name);
            if (files != null && files.has(name)) {
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(files.getString(name).getBytes("UTF-8"));
                }
            } else if (f.isFile()) {
                f.delete();   // it did not exist when the backup was made
            }
        }
    }

    private static Uri findInDownloads(ContentResolver resolver) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try (Cursor c = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, MediaStore.Downloads.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (RuntimeException ignored) {
            // no access: fall back to the copy
        }
        return null;
    }

    private static String readFromDownloads(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = findInDownloads(resolver);
            if (uri == null) return null;
            return read(resolver.openInputStream(uri));
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = stream.read(buf)) > 0; ) bytes.write(buf, 0, n);
            return bytes.toString("UTF-8");
        }
    }
}
