package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Local patch, not an upstream feature: custom per-app names set from the library's "Rename" tile action
 * (AppLibraryActivity). Applied wherever that package's tile shows a label — the library itself and Home's
 * dock alike (AppAdapter.reload reads this regardless of lockToDock), so a rename follows the app everywhere.
 */
final class LibraryNames {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_NAME_LENGTH = 60;
    private static final int MAX_BYTES = MAX_ENTRIES * (255 + MAX_NAME_LENGTH + 2);

    private LibraryNames() { }

    private static AtomicFile store(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "library_names.txt"));
    }

    static Map<String, String> load(Context context) {
        AtomicFile file = store(context);
        try {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
                return Collections.emptyMap();
            try (FileInputStream input = file.openRead()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_BYTES) throw new IOException("Names file too large");
                    bytes.write(buffer, 0, count);
                }
                return decode(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Log.w("TvHome.Library", "Could not read custom names", e);
            return Collections.emptyMap();
        }
    }

    /** A blank or null name clears the override, back to the app's own label. */
    static boolean save(Context context, String pkg, String name) {
        if (!LibraryApps.validPackage(pkg)) return false;
        Map<String, String> next = new HashMap<>(load(context));
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            next.remove(pkg);
        } else {
            if (trimmed.length() > MAX_NAME_LENGTH) trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
            next.put(pkg, trimmed);
        }
        if (next.size() > MAX_ENTRIES) return false;
        AtomicFile file = store(context);
        FileOutputStream output = null;
        try {
            byte[] bytes = encode(next).getBytes(StandardCharsets.UTF_8);
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
            return true;
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            Log.w("TvHome.Library", "Could not save custom name", e);
            return false;
        }
    }

    private static String encode(Map<String, String> names) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            String clean = e.getValue().replace('\t', ' ').replace('\n', ' ');
            text.append(e.getKey()).append('\t').append(clean).append('\n');
        }
        return text.toString();
    }

    private static Map<String, String> decode(String text) {
        Map<String, String> result = new HashMap<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != '\n') continue;
            if (i == start && i == text.length()) break;
            String line = text.substring(start, i);
            int tab = line.indexOf('\t');
            if (tab > 0) {
                String pkg = line.substring(0, tab);
                if (LibraryApps.validPackage(pkg)) result.put(pkg, line.substring(tab + 1));
            }
            start = i + 1;
        }
        return result;
    }
}
