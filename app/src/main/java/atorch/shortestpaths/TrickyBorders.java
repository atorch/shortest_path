package atorch.shortestpaths;

import android.content.res.Resources;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Borders that players often don't expect (like Spain and Morocco, via Ceuta and Melilla),
 * and pairs that look like neighbours but aren't in the puzzle's map (like Morocco and
 * Mauritania). Loaded from res/values/tricky_borders.xml.
 */
final class TrickyBorders {

    private static final String TAG = "TrickyBorders";

    private final Map<String, String> trickyNotes = new HashMap<>();
    private final Map<String, String> missingNotes = new HashMap<>();

    TrickyBorders(Resources res) {
        load(res.getStringArray(R.array.tricky_borders), trickyNotes);
        load(res.getStringArray(R.array.missing_borders), missingNotes);
    }

    /** True if crossing from a to b (in either direction) is a border players tend to miss. */
    boolean isTricky(String a, String b) {
        return trickyNotes.containsKey(key(a, b));
    }

    /** The explanation to show when crossing from a to b, or null if it's an ordinary border. */
    String crossingNote(String a, String b) {
        return trickyNotes.get(key(a, b));
    }

    /** The explanation to show when a player tries to go from a to b but the puzzle has no such border, or null. */
    String missingBorderNote(String a, String b) {
        return missingNotes.get(key(a, b));
    }

    private static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static void load(String[] items, Map<String, String> into) {
        for (String item : items) {
            String[] parts = item.split("\\|", 3);
            if (parts.length != 3) {
                Log.w(TAG, "Skipping malformed entry: " + item);
                continue;
            }
            into.put(key(parts[0].trim(), parts[1].trim()), parts[2].trim());
        }
    }
}
