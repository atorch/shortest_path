package atorch.shortestpaths;

import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Map screen (issue #4). Four modes:
 * <ul>
 *   <li>STUDY: the whole world with every puzzle country named; tap a country to see its name.
 *       Names can be hidden from the menu, to quiz yourself by tapping.</li>
 *   <li>REVIEW: after solving a puzzle, the route we took is highlighted and named.</li>
 *   <li>ANSWER: after giving up, one of the shortest routes is highlighted and named.</li>
 *   <li>HINT: while solving, borders only. Just the start, the destination and the countries we have already
 *       passed through are named, and tapping doesn't reveal anything.</li>
 * </ul>
 */
public class ReviewMap extends AppCompatActivity {

    public static final String EXTRA_MODE = "atorch.shortestpaths.MAP_MODE";
    public static final String EXTRA_FROM = "atorch.shortestpaths.MAP_FROM";
    public static final String EXTRA_TO = "atorch.shortestpaths.MAP_TO";
    public static final String EXTRA_ROUTE = "atorch.shortestpaths.MAP_ROUTE";  // Countries between FROM and TO

    public static final int MODE_STUDY = 0;
    public static final int MODE_REVIEW = 1;
    public static final int MODE_HINT = 2;
    public static final int MODE_ANSWER = 3;

    private static final int COLOR_START = 0xFF93D293;
    private static final int COLOR_DESTINATION = 0xFFF2A48E;
    private static final int COLOR_ROUTE = 0xFFA3C6EE;
    private static final int COLOR_SELECTED = 0xFFF6DC7A;

    private static final String STATE_VIEWPORT = "viewport";
    private static final String STATE_SHOW_NAMES = "show_names";
    private static final String STATE_SELECTED = "selected";

    private CountryMapView mapView;
    private TextView status;
    private int mode;
    private boolean showNames = true;
    private String selected;
    private final Map<String, Integer> routeHighlights = new LinkedHashMap<>();

    public static Intent studyIntent(Context context) {
        Intent intent = new Intent(context, ReviewMap.class);
        intent.putExtra(EXTRA_MODE, MODE_STUDY);
        return intent;
    }

    public static Intent routeIntent(Context context, int mode, String from, String to, List<String> route) {
        Intent intent = new Intent(context, ReviewMap.class);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_FROM, from);
        intent.putExtra(EXTRA_TO, to);
        intent.putExtra(EXTRA_ROUTE, route.toArray(new String[0]));
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_map);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        WorldMap worldMap;
        try {
            worldMap = WorldMap.get(this);
        } catch (IOException e) {
            Toast.makeText(this, R.string.map_load_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Intent intent = getIntent();
        mode = intent.getIntExtra(EXTRA_MODE, MODE_STUDY);
        String from = intent.getStringExtra(EXTRA_FROM);
        String to = intent.getStringExtra(EXTRA_TO);
        String[] routeExtra = intent.getStringArrayExtra(EXTRA_ROUTE);
        List<String> route = routeExtra == null ? new ArrayList<String>() : Arrays.asList(routeExtra);

        mapView = findViewById(R.id.country_map);
        status = findViewById(R.id.map_status);
        TextView legend = findViewById(R.id.map_legend);
        mapView.setMap(worldMap);

        if (savedInstanceState != null) {
            showNames = savedInstanceState.getBoolean(STATE_SHOW_NAMES, true);
            selected = savedInstanceState.getString(STATE_SELECTED);
        }

        switch (mode) {
            case MODE_REVIEW:
                setTitle(R.string.title_activity_review_map);
                break;
            case MODE_ANSWER:
                setTitle(R.string.title_activity_answer_map);
                break;
            case MODE_HINT:
                setTitle(R.string.title_activity_hint_map);
                break;
            default:
                setTitle(R.string.title_activity_study_map);
        }

        List<String> allCountries = new ArrayList<>();
        if (mode != MODE_STUDY && from != null && to != null) {
            routeHighlights.put(from, COLOR_START);
            for (String country : route) {
                routeHighlights.put(country, COLOR_ROUTE);
            }
            routeHighlights.put(to, COLOR_DESTINATION);
            for (Map.Entry<String, Integer> entry : routeHighlights.entrySet()) {
                mapView.highlightCountry(entry.getKey(), entry.getValue(), true);
            }
            allCountries.add(from);
            allCountries.addAll(route);
            allCountries.add(to);
            legend.setText(buildLegend(from, route, to));
            legend.setVisibility(View.VISIBLE);
        }

        if (mode == MODE_HINT) {
            showNames = false;
            status.setText(R.string.map_hint_explanation);
            status.setVisibility(View.VISIBLE);
        } else {
            mapView.setOnCountryTapListener(new CountryMapView.OnCountryTapListener() {
                @Override
                public void onCountryTapped(String countryName, boolean onLand) {
                    select(countryName, onLand);
                }
            });
            status.setText(R.string.map_tap_explanation);
            status.setVisibility(View.VISIBLE);
            if (selected != null) {
                select(selected, true);
            }
        }
        applyLabelMode();

        float[] viewport = savedInstanceState == null ? null : savedInstanceState.getFloatArray(STATE_VIEWPORT);
        if (viewport != null) {
            mapView.setViewport(viewport);
        } else if (!allCountries.isEmpty()) {
            RectF bounds = mapView.boundsOf(allCountries);
            if (bounds != null) {
                // Hints zoom out a little more, so that some of the neighbours are visible too
                mapView.fitTo(bounds, mode == MODE_HINT ? 0.6f : 0.25f);
            }
        }
    }

    private CharSequence buildLegend(String from, List<String> route, String to) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int intro;
        if (mode == MODE_REVIEW) {
            intro = R.string.map_legend_review;
        } else if (mode == MODE_ANSWER) {
            intro = R.string.map_legend_answer;
        } else {
            intro = R.string.map_legend_hint;
        }
        text.append(getString(intro)).append("\n");
        appendCountry(text, from, COLOR_START);
        for (String country : route) {
            text.append(" → ");
            appendCountry(text, country, COLOR_ROUTE);
        }
        text.append(" → ");
        if (mode == MODE_HINT) {
            text.append("… → ");
        }
        appendCountry(text, to, COLOR_DESTINATION);
        return text;
    }

    private static void appendCountry(SpannableStringBuilder text, String country, int color) {
        int start = text.length();
        text.append("●");
        text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" ");
        int nameStart = text.length();
        text.append(country);
        text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), nameStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void select(String countryName, boolean onLand) {
        if (selected != null) {
            mapView.clearHighlight(selected);
            Integer routeColor = routeHighlights.get(selected);
            if (routeColor != null) {
                mapView.highlightCountry(selected, routeColor, true);
            }
        }
        selected = countryName;
        if (countryName != null) {
            mapView.highlightCountry(countryName, COLOR_SELECTED, true);
            status.setText(countryName);
        } else if (onLand) {
            status.setText(R.string.map_not_in_puzzles);
        } else {
            status.setText(R.string.map_tap_explanation);
        }
        status.setVisibility(View.VISIBLE);
    }

    private void applyLabelMode() {
        mapView.setLabelMode(showNames ? CountryMapView.LabelMode.ALL : CountryMapView.LabelMode.HIGHLIGHTED_ONLY);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mode == MODE_HINT) {
            return false;  // No way to turn the names back on during a puzzle
        }
        getMenuInflater().inflate(R.menu.review_map, menu);
        menu.findItem(R.id.action_show_names).setChecked(showNames);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();  // Behave like "back": we can be opened from the main menu or from a puzzle
            return true;
        } else if (id == R.id.action_show_names) {
            showNames = !showNames;
            item.setChecked(showNames);
            applyLabelMode();
            return true;
        } else if (id == R.id.action_show_world) {
            mapView.fitWorld();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            outState.putFloatArray(STATE_VIEWPORT, mapView.getViewport());
        }
        outState.putBoolean(STATE_SHOW_NAMES, showNames);
        outState.putString(STATE_SELECTED, selected);
    }
}
