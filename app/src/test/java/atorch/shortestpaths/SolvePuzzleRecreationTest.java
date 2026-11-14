package atorch.shortestpaths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android can destroy and recreate SolvePuzzle at any time: when the app is killed in the background
 * and the user comes back to it, or when a setting such as dark mode, font size or language changes.
 * The user should get back the same puzzle, with the countries they've already entered, and be able
 * to carry on solving it.
 */
@RunWith(RobolectricTestRunner.class)
public class SolvePuzzleRecreationTest {

    // Level 1 puzzles need 3 or 4 countries, so there's progress to keep after entering one
    private static final int LEVEL = 1;

    private String[] countries;

    @Before
    public void fillDatabase() {
        // MainActivity fills the puzzle database the first time the app is opened
        Robolectric.buildActivity(MainActivity.class).setup();
        countries = RuntimeEnvironment.getApplication().getResources().getStringArray(R.array.countries);
    }

    @Test
    public void puzzleSurvivesConfigurationChange() {
        ActivityController<SolvePuzzle> controller = startPuzzle();
        String[] path = shortestPath(controller.get());
        enterCountry(controller.get(), path[0]);
        PuzzleScreen before = PuzzleScreen.of(controller.get());

        controller.recreate();  // e.g. the user switches dark mode on mid-puzzle

        assertSamePuzzleAndProgress(before, controller.get());
        assertCanCarryOn(controller.get(), path, before);
    }

    @Test
    public void puzzleSurvivesProcessDeath() {
        ActivityController<SolvePuzzle> controller = startPuzzle();
        String[] path = shortestPath(controller.get());
        enterCountry(controller.get(), path[0]);
        PuzzleScreen before = PuzzleScreen.of(controller.get());

        // The user leaves the app, Android kills it, and later the user comes back: a brand new
        // SolvePuzzle is created from nothing but the saved instance state
        Bundle savedState = new Bundle();
        controller.pause().stop().saveInstanceState(savedState).destroy();
        ActivityController<SolvePuzzle> restored =
                Robolectric.buildActivity(SolvePuzzle.class, puzzleIntent()).setup(savedState);

        assertSamePuzzleAndProgress(before, restored.get());
        assertCanCarryOn(restored.get(), path, before);
    }

    private ActivityController<SolvePuzzle> startPuzzle() {
        return Robolectric.buildActivity(SolvePuzzle.class, puzzleIntent()).setup();
    }

    private static Intent puzzleIntent() {
        return new Intent(RuntimeEnvironment.getApplication(), SolvePuzzle.class)
                .putExtra(MainActivity.LEVEL, LEVEL);
    }

    private static void assertSamePuzzleAndProgress(PuzzleScreen before, SolvePuzzle activity) {
        AutoCompleteTextView answerField = activity.findViewById(R.id.autocomplete_country);
        assertNotNull("After recreation the puzzle screen is blank: there's no answer field", answerField);
        assertEquals(View.VISIBLE, answerField.getVisibility());
        assertEquals("Different puzzle after recreation", before.statement, PuzzleScreen.of(activity).statement);
        assertEquals("Countries entered so far were lost", before.route, PuzzleScreen.of(activity).route);
    }

    /** The next country on the same shortest path is still accepted, and shows up on the route. */
    private static void assertCanCarryOn(SolvePuzzle activity, String[] path, PuzzleScreen before) {
        enterCountry(activity, path[1]);
        String[] route = PuzzleScreen.of(activity).route;
        assertTrue("The second correct country wasn't accepted after recreation: " + Arrays.toString(route),
                Arrays.asList(route).contains(path[1]));
        assertEquals(before.route.length + 1, route.length);
    }

    /** One of the shortest paths for the puzzle on screen, as the countries the user has to enter. */
    private String[] shortestPath(SolvePuzzle activity) {
        PuzzleScreen screen = PuzzleScreen.of(activity);
        String from = screen.route[0];
        String to = null;
        String format = activity.getString(R.string.puzzle_statement_format_string);
        for (String country : countries) {
            String statement = HtmlCompat.fromHtml(String.format(format, from, country),
                    HtmlCompat.FROM_HTML_MODE_LEGACY).toString();
            if (statement.equals(screen.statement)) {
                to = country;
            }
        }
        assertNotNull("Couldn't work out the destination from: " + screen.statement, to);

        SQLiteDatabase db = new MySQLiteHelper(activity).getReadableDatabase();
        try (Cursor c = db.query(MySQLiteHelper.PATH_TABLE_NAME, new String[]{MySQLiteHelper.COL_PATH},
                MySQLiteHelper.COL_FROM + "=? and " + MySQLiteHelper.COL_TO + "=?",
                new String[]{String.valueOf(indexOf(from)), String.valueOf(indexOf(to))},
                null, null, null, "1")) {
            assertTrue("No path stored from " + from + " to " + to, c.moveToFirst());
            String[] indices = c.getString(0).split(";");
            String[] path = new String[indices.length];
            for (int i = 0; i < indices.length; i++) {
                path[i] = countries[Integer.parseInt(indices[i])];
            }
            assertTrue("Expected a level " + LEVEL + " puzzle with at least 3 countries to enter", path.length >= 3);
            return path;
        }
    }

    private int indexOf(String country) {
        int index = Arrays.asList(countries).indexOf(country);
        assertTrue("Unknown country " + country, index >= 0);
        return index;
    }

    /** What the user does: pick a country from the answer field's suggestions. */
    private static void enterCountry(SolvePuzzle activity, String country) {
        AutoCompleteTextView answerField = activity.findViewById(R.id.autocomplete_country);
        assertNotNull("No answer field on screen", answerField);
        ListAdapter suggestions = answerField.getAdapter();
        for (int position = 0; position < suggestions.getCount(); position++) {
            if (country.equals(suggestions.getItem(position).toString())) {
                answerField.getOnItemClickListener().onItemClick(null, answerField, position, position);
                return;
            }
        }
        throw new AssertionError(country + " isn't one of the suggestions");
    }

    /** The parts of the puzzle screen that should look the same after recreation. */
    private static final class PuzzleScreen {
        final String statement;
        final String[] route;  // The start, then each country entered so far

        private PuzzleScreen(String statement, String[] route) {
            this.statement = statement;
            this.route = route;
        }

        static PuzzleScreen of(SolvePuzzle activity) {
            TextView statement = activity.findViewById(R.id.puzzle_statement);
            LinearLayout countriesVisited = activity.findViewById(R.id.countries_visited);
            assertNotNull("After recreation the puzzle screen is blank: there's no puzzle statement", statement);
            assertNotNull("After recreation the puzzle screen is blank: there's no route", countriesVisited);
            // The route is a column of country names with arrows between them
            List<String> route = new ArrayList<>();
            for (int i = 0; i < countriesVisited.getChildCount(); i++) {
                View child = countriesVisited.getChildAt(i);
                if (child instanceof TextView) {
                    route.add(((TextView) child).getText().toString());
                }
            }
            return new PuzzleScreen(statement.getText().toString(), route.toArray(new String[0]));
        }
    }
}
