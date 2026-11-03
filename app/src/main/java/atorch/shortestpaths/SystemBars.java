package atorch.shortestpaths;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge setup shared by every screen.
 *
 * <p>Apps targeting API 35+ are always drawn edge-to-edge on Android 15+ (and API 36 removed the opt-out),
 * so the app now draws behind the status and navigation bars on every Android version.
 * The AppCompat action bar keeps itself below the status bar. Content needs padding so that the
 * navigation bar and display cutouts don't cover it.
 */
final class SystemBars {

    // The same scrims androidx.activity uses by default
    private static final int LIGHT_SCRIM = Color.argb(0xe6, 0xff, 0xff, 0xff);
    private static final int DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b);

    private SystemBars() {
    }

    /**
     * Call right after setContentView. Draws behind the system bars with dark icons (our theme is
     * always light, even when the phone is in dark mode), then pads the layout's root view
     * so the navigation bar and cutouts don't cover the content.
     */
    static void setUp(ComponentActivity activity) {
        EdgeToEdge.enable(activity,
                SystemBarStyle.light(Color.TRANSPARENT, DARK_SCRIM),
                SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM));

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            padForSystemBars(content.getChildAt(0));
        }
    }

    /**
     * Pads the left, right and bottom of view so the system bars and cutouts don't cover it.
     * The top is left alone because the action bar already sits below the status bar.
     * Scrolling views can still scroll behind the navigation bar, and their last item
     * scrolls fully into view.
     */
    static void padForSystemBars(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setClipToPadding(false);
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
