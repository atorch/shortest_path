package atorch.shortestpaths;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A minimal, offline, pannable / zoomable map of country borders, drawn with plain Canvas calls.
 *
 * Deliberately shows nothing but land, sea, borders and (optionally) puzzle country names.
 * Callers can highlight countries with a fill colour and choose which names are shown.
 */
public class CountryMapView extends View {

    public interface OnCountryTapListener {
        /**
         * @param countryName the puzzle country that was tapped, or null for the sea or for land that is not
         *                    part of the puzzles (e.g. Greenland)
         * @param onLand      whether the tap hit any land at all
         */
        void onCountryTapped(String countryName, boolean onLand);
    }

    public enum LabelMode { ALL, HIGHLIGHTED_ONLY, NONE }

    private static final int COLOR_SEA = 0xFFDCE8F0;
    private static final int COLOR_LAND = 0xFFF7F4EC;
    private static final int COLOR_OTHER_LAND = 0xFFE4E2DC;  // Territories that never appear in puzzles
    private static final int COLOR_BORDER = 0xFF6E6E6E;
    private static final int COLOR_LABEL = 0xFF2B2B2B;
    private static final int COLOR_LABEL_HALO = 0xDDFFFFFF;

    private static final float MAX_PIXELS_PER_UNIT = 400000f;  // Deep enough to see Vatican City's outline
    private static final float MAX_LOD_ERROR_PX = 0.75f;

    private WorldMap map;
    private final List<WorldMap.Feature> drawOrder = new ArrayList<>();  // Big first, so enclaves end up on top

    private final Map<WorldMap.Feature, Integer> highlightColors = new HashMap<>();
    private final Set<WorldMap.Feature> labelledHighlights = new HashSet<>();
    private LabelMode labelMode = LabelMode.ALL;
    private OnCountryTapListener tapListener;

    // Viewport: screenX = mapX * scale + translateX (same for y)
    private float scale = 1f;
    private float translateX = 0f;
    private float translateY = 0f;
    private float minScale = 1f;
    private RectF pendingFit;  // Fit request made before the view was laid out
    private float[] pendingViewport;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float borderWidthPx;
    private final float dotRadiusPx;
    private final Map<WorldMap.Feature, Float> labelWidths = new HashMap<>();
    private final List<RectF> placedLabels = new ArrayList<>();
    private final RectF visible = new RectF();
    private final RectF scratch = new RectF();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    public CountryMapView(Context context) {
        this(context, null);
    }

    public CountryMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        borderWidthPx = 1.0f * density;
        dotRadiusPx = 3.5f * density;

        fillPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStrokeJoin(Paint.Join.ROUND);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, getResources().getDisplayMetrics()));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        haloPaint.set(labelPaint);
        haloPaint.setColor(COLOR_LABEL_HALO);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(3f * density);
        haloPaint.setStrokeJoin(Paint.Join.ROUND);

        scroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                translateX -= dx;
                translateY -= dy;
                clampTranslation();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (map == null) {
                    return false;
                }
                float[] range = translationRange();
                scroller.fling((int) translateX, (int) translateY, (int) vx, (int) vy,
                        (int) range[0], (int) range[1], (int) range[2], (int) range[3]);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomBy(2f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null && map != null) {
                    WorldMap.Feature hit = featureAt(e.getX(), e.getY());
                    tapListener.onCountryTapped(hit == null ? null : map.countryNameFor(hit), hit != null);
                }
                return true;
            }
        });
    }

    public void setMap(WorldMap map) {
        this.map = map;
        drawOrder.clear();
        drawOrder.addAll(map.features);
        Collections.sort(drawOrder, new Comparator<WorldMap.Feature>() {
            @Override
            public int compare(WorldMap.Feature a, WorldMap.Feature b) {
                return Float.compare(b.area, a.area);
            }
        });
        if (getWidth() > 0) {
            updateMinScale();
        }
        invalidate();
    }

    /** Fill every map unit of a puzzle country with a colour; labelled highlights stay visible in HIGHLIGHTED_ONLY. */
    public void highlightCountry(String countryName, int color, boolean showLabel) {
        if (map == null) {
            return;
        }
        for (WorldMap.Feature feature : map.featuresFor(countryName)) {
            highlightColors.put(feature, color);
        }
        WorldMap.Feature primary = map.primaryFeatureFor(countryName);
        if (primary != null && showLabel) {
            labelledHighlights.add(primary);
        }
        invalidate();
    }

    public void clearHighlight(String countryName) {
        if (map == null) {
            return;
        }
        for (WorldMap.Feature feature : map.featuresFor(countryName)) {
            highlightColors.remove(feature);
            labelledHighlights.remove(feature);
        }
        invalidate();
    }

    public void setLabelMode(LabelMode mode) {
        labelMode = mode;
        invalidate();
    }

    public void setOnCountryTapListener(OnCountryTapListener listener) {
        tapListener = listener;
    }

    /** Bounds (in map coordinates) of the primary map units of these puzzle countries, or null if none are known. */
    public RectF boundsOf(List<String> countryNames) {
        RectF result = null;
        for (String name : countryNames) {
            WorldMap.Feature primary = map == null ? null : map.primaryFeatureFor(name);
            if (primary == null) {
                continue;
            }
            if (result == null) {
                result = new RectF(primary.bounds);
            } else {
                result.union(primary.bounds);
            }
        }
        return result;
    }

    /** Zoom so that rect (map coordinates) fills the view, leaving a margin of `margin` times its size. */
    public void fitTo(RectF rect, float margin) {
        RectF padded = new RectF(rect);
        float minSize = 0.01f;  // Don't zoom all the way in on a microstate
        float w = Math.max(padded.width(), minSize) * (1 + 2 * margin);
        float h = Math.max(padded.height(), minSize) * (1 + 2 * margin);
        padded.set(padded.centerX() - w / 2, padded.centerY() - h / 2, padded.centerX() + w / 2, padded.centerY() + h / 2);
        if (getWidth() == 0 || getHeight() == 0) {
            pendingFit = padded;
            return;
        }
        scale = clampScale(Math.min(getWidth() / padded.width(), getHeight() / padded.height()));
        translateX = getWidth() / 2f - padded.centerX() * scale;
        translateY = getHeight() / 2f - padded.centerY() * scale;
        clampTranslation();
        invalidate();
    }

    public void fitWorld() {
        if (map != null) {
            fitTo(map.bounds, 0f);
        }
    }

    /** {centerX, centerY, pixelsPerUnit}, for saving across configuration changes. */
    public float[] getViewport() {
        if (getWidth() == 0) {
            return pendingViewport;
        }
        return new float[]{(getWidth() / 2f - translateX) / scale, (getHeight() / 2f - translateY) / scale, scale};
    }

    public void setViewport(float[] viewport) {
        if (viewport == null) {
            return;
        }
        if (getWidth() == 0) {
            pendingViewport = viewport;
            pendingFit = null;
            return;
        }
        scale = clampScale(viewport[2]);
        translateX = getWidth() / 2f - viewport[0] * scale;
        translateY = getHeight() / 2f - viewport[1] * scale;
        clampTranslation();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float[] keep = null;
        if (oldW > 0 && oldH > 0) {
            keep = new float[]{(oldW / 2f - translateX) / scale, (oldH / 2f - translateY) / scale, scale};
        }
        updateMinScale();
        if (pendingViewport != null) {
            float[] viewport = pendingViewport;
            pendingViewport = null;
            setViewport(viewport);
        } else if (pendingFit != null) {
            RectF fit = pendingFit;
            pendingFit = null;
            fitTo(fit, 0f);
        } else if (keep != null) {
            setViewport(keep);
        } else {
            fitWorld();
        }
    }

    private void updateMinScale() {
        if (map == null || getWidth() == 0) {
            return;
        }
        minScale = Math.min(getWidth() / map.bounds.width(), getHeight() / map.bounds.height());
    }

    private float clampScale(float s) {
        return Math.max(minScale, Math.min(MAX_PIXELS_PER_UNIT, s));
    }

    private void zoomBy(float factor, float focusX, float focusY) {
        float newScale = clampScale(scale * factor);
        float applied = newScale / scale;
        translateX = focusX - (focusX - translateX) * applied;
        translateY = focusY - (focusY - translateY) * applied;
        scale = newScale;
        clampTranslation();
        invalidate();
    }

    /** {minX, maxX, minY, maxY} for the translation: the view's centre must stay over the map. */
    private float[] translationRange() {
        RectF b = map.bounds;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float minX = cx - b.right * scale, maxX = cx - b.left * scale;
        float minY = cy - b.bottom * scale, maxY = cy - b.top * scale;
        // When the whole map fits in one dimension, just centre it
        if (b.width() * scale <= getWidth()) {
            minX = maxX = cx - b.centerX() * scale;
        }
        if (b.height() * scale <= getHeight()) {
            minY = maxY = cy - b.centerY() * scale;
        }
        return new float[]{minX, maxX, minY, maxY};
    }

    private void clampTranslation() {
        if (map == null) {
            return;
        }
        float[] range = translationRange();
        translateX = Math.max(range[0], Math.min(range[1], translateX));
        translateY = Math.max(range[2], Math.min(range[3], translateY));
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            translateX = scroller.getCurrX();
            translateY = scroller.getCurrY();
            clampTranslation();
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled = gestureDetector.onTouchEvent(event) || handled;
        return handled || super.onTouchEvent(event);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Drawing

    private boolean isDot(WorldMap.Feature f) {
        // Microstates (and other tiny units) get a dot so they can be seen and tapped at any zoom
        float threshold = 2.5f * dotRadiusPx;
        return f.bounds.width() * scale < threshold && f.bounds.height() * scale < threshold;
    }

    private int fillColorFor(WorldMap.Feature f) {
        Integer highlight = highlightColors.get(f);
        if (highlight != null) {
            return highlight;
        }
        return map.countryNameFor(f) != null ? COLOR_LAND : COLOR_OTHER_LAND;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(COLOR_SEA);
        if (map == null) {
            return;
        }
        visible.set(-translateX / scale, -translateY / scale,
                (getWidth() - translateX) / scale, (getHeight() - translateY) / scale);
        int lod = map.lodFor(scale, MAX_LOD_ERROR_PX);

        canvas.save();
        canvas.translate(translateX, translateY);
        canvas.scale(scale, scale);
        borderPaint.setStrokeWidth(borderWidthPx / scale);
        for (WorldMap.Feature f : drawOrder) {
            if (!RectF.intersects(visible, f.bounds)) {
                continue;
            }
            fillPaint.setColor(fillColorFor(f));
            canvas.drawPath(f.getPath(lod), fillPaint);
            canvas.drawPath(f.getPath(lod), borderPaint);
        }
        canvas.restore();

        // Dots for tiny puzzle countries, drawn in screen space
        borderPaint.setStrokeWidth(borderWidthPx);
        for (WorldMap.Feature f : drawOrder) {
            if (!f.isLabelled() || !isDot(f) || !visible.contains(f.labelX, f.labelY)) {
                continue;
            }
            float x = f.labelX * scale + translateX;
            float y = f.labelY * scale + translateY;
            fillPaint.setColor(highlightColors.containsKey(f) ? highlightColors.get(f) : Color.WHITE);
            canvas.drawCircle(x, y, dotRadiusPx, fillPaint);
            canvas.drawCircle(x, y, dotRadiusPx, borderPaint);
        }

        drawLabels(canvas);
    }

    private void drawLabels(Canvas canvas) {
        if (labelMode == LabelMode.NONE) {
            return;
        }
        // Highlighted labels first (they must win collisions), then bigger countries first
        List<WorldMap.Feature> candidates = new ArrayList<>();
        for (WorldMap.Feature f : labelledHighlights) {
            if (f.isLabelled()) {
                candidates.add(f);
            }
        }
        if (labelMode == LabelMode.ALL) {
            for (WorldMap.Feature f : drawOrder) {
                if (f.isLabelled() && !labelledHighlights.contains(f)) {
                    candidates.add(f);
                }
            }
        }

        placedLabels.clear();
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        float pad = 2f * density;
        for (WorldMap.Feature f : candidates) {
            if (!visible.contains(f.labelX, f.labelY)) {
                continue;
            }
            Float width = labelWidths.get(f);
            if (width == null) {
                width = labelPaint.measureText(f.label);
                labelWidths.put(f, width);
            }
            boolean important = labelledHighlights.contains(f);
            boolean dot = isDot(f);
            // Skip names that are much wider than their country, unless highlighted (or drawn as a dot)
            if (!important && !dot && f.bounds.width() * scale < 0.6f * width) {
                continue;
            }
            float x = f.labelX * scale + translateX;
            float y = f.labelY * scale + translateY;
            if (dot) {
                y += dotRadiusPx + textHeight / 2;  // Name goes under the dot
            }
            float baseline = y - (fm.ascent + fm.descent) / 2;
            scratch.set(x - width / 2 - pad, y - textHeight / 2 - pad, x + width / 2 + pad, y + textHeight / 2 + pad);
            boolean collides = false;
            for (RectF placed : placedLabels) {
                if (RectF.intersects(placed, scratch)) {
                    collides = true;
                    break;
                }
            }
            if (collides && !important) {
                continue;
            }
            placedLabels.add(new RectF(scratch));
            canvas.drawText(f.label, x, baseline, haloPaint);
            canvas.drawText(f.label, x, baseline, labelPaint);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hit testing

    private WorldMap.Feature featureAt(float screenX, float screenY) {
        // Dots first: they are the only practical way to tap a microstate
        float slop = 2f * dotRadiusPx;
        for (int i = drawOrder.size() - 1; i >= 0; i--) {
            WorldMap.Feature f = drawOrder.get(i);
            if (f.isLabelled() && isDot(f)) {
                float dx = f.labelX * scale + translateX - screenX;
                float dy = f.labelY * scale + translateY - screenY;
                if (dx * dx + dy * dy <= slop * slop) {
                    return f;
                }
            }
        }
        float x = (screenX - translateX) / scale;
        float y = (screenY - translateY) / scale;
        for (int i = drawOrder.size() - 1; i >= 0; i--) {  // Smallest first: enclaves sit on top
            WorldMap.Feature f = drawOrder.get(i);
            if (f.contains(x, y)) {
                return f;
            }
        }
        return null;
    }
}
