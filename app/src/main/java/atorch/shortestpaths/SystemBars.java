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
 *
 * <p>Edge-to-edge also means windowSoftInputMode="adjustResize" no longer resizes the window when the
 * keyboard opens, so we do the resizing ourselves (see {@link #fitToSystemBarsAndKeyboard}).
 */
final class SystemBars {

    // The same scrims androidx.activity uses by default
    private static final int LIGHT_SCRIM = Color.argb(0xe6, 0xff, 0xff, 0xff);
    private static final int DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b);

    private SystemBars() {
    }

    /**
     * Call right after setContentView. Draws behind the system bars with dark icons (our theme is
     * always light, even when the phone is in dark mode), then fits the layout's root view between
     * the action bar, the navigation bar, cutouts and the keyboard.
     */
    static void setUp(ComponentActivity activity) {
        EdgeToEdge.enable(activity,
                SystemBarStyle.light(Color.TRANSPARENT, DARK_SCRIM),
                SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM));

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            fitToSystemBarsAndKeyboard(content.getChildAt(0));
        }
    }

    /**
     * Keeps view clear of the navigation bar, cutouts and the keyboard. The top is left alone
     * because the action bar already sits below the status bar.
     *
     * <ul>
     *   <li>Navigation bar and cutouts: padding, with clipToPadding off, so scrolling content still
     *       draws behind the (translucent) navigation bar, and its last item can scroll fully into view.</li>
     *   <li>Keyboard: a bottom margin, which shrinks the view to the area above the keyboard, just like
     *       adjustResize used to. A ScrollView then keeps the focused text field in view by itself
     *       (ScrollView#onSizeChanged, and the TextView requesting its cursor rectangle on screen).
     *       Padding wouldn't do that: ScrollView ignores its padding when deciding whether a child is
     *       already visible.</li>
     * </ul>
     */
    static void fitToSystemBarsAndKeyboard(View view) {
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
            // Measured from the bottom of the window, so it includes the navigation bar while showing
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;

            // The keyboard covers the navigation bar, so only pad for whatever part of it the keyboard doesn't cover
            v.setPadding(left + bars.left, top, right + bars.right, bottom + Math.max(bars.bottom - keyboard, 0));

            ViewGroup.LayoutParams params = v.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) params).bottomMargin != keyboard) {
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = keyboard;
                v.setLayoutParams(params);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
