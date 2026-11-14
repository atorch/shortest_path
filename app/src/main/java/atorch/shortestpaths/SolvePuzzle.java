package atorch.shortestpaths;

import android.content.ContentValues;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;

public class SolvePuzzle extends AppCompatActivity {

    // Saved instance state: enough to rebuild the puzzle after Android recreates this activity
    // (for example after killing the app in the background, or when dark mode is switched on)
    private static final String STATE_FROM = "atorch.shortestpaths.STATE_FROM";
    private static final String STATE_TO = "atorch.shortestpaths.STATE_TO";
    private static final String STATE_ROUTE = "atorch.shortestpaths.STATE_ROUTE";

    private TextView root_country;
    private ImageView arrow;
    private AutoCompleteTextView country_input;

    private int level;

    private String[] congratulations_array;

    private String[] countries;
    private int country_from_index;
    private int country_to_index;
    private String country_from;
    private String country_to;

    private int path_length;
    private int path_length_inner;  // Equal to path_length - 2, i.e. number of countries user has to input
    private int number_of_paths;
    private String[][] paths;  // Paths[i] is an array of country names
    private boolean[] path_indicators;  // When user's guess is consistent with paths[i], path_indicators[i] will be true
    private int user_progress;  // How far along path user has traveled
    private final ArrayList<String> user_route = new ArrayList<>();  // Countries the user has correctly entered, in order

    private SQLiteDatabase db;

    public static final Random random = new Random();

    private Cursor getSummaryCursor(int path_length_lower, int path_length_upper, SQLiteDatabase db) {
        String[] projection = {MySQLiteHelper.COL_FROM, MySQLiteHelper.COL_TO, MySQLiteHelper.COL_PATH_LENGTH};
        String where_path_length = MySQLiteHelper.COL_PATH_LENGTH + "=? or " + MySQLiteHelper.COL_PATH_LENGTH + "=?";
        String[] path_length_conditions = {String.valueOf(path_length_lower), String.valueOf(path_length_upper)};
        Cursor c = db.query(MySQLiteHelper.SUMMARY_TABLE_NAME, projection, where_path_length, path_length_conditions, null, null, "RANDOM()", "1");
        c.moveToFirst();
        return c;
    }

    private Cursor getPathCursor(int country_from_index, int country_to_index, SQLiteDatabase db) {
        String[] projection_path = {MySQLiteHelper.COL_PATH};
        String where_from_to = MySQLiteHelper.COL_FROM + "=? and " + MySQLiteHelper.COL_TO + "=?";
        String[] from_to_conditions = {String.valueOf(country_from_index), String.valueOf(country_to_index)};
        return db.query(MySQLiteHelper.PATH_TABLE_NAME, projection_path, where_from_to, from_to_conditions, null, null, null);
    }

    private void setPuzzleStatement(Resources res) {
        String puzzle_statement_text = String.format(res.getString(R.string.puzzle_statement_format_string), country_from, country_to);
        String path_length_statement_text = res.getQuantityString(R.plurals.path_length_statement, path_length_inner, path_length_inner);
        TextView puzzle_statement = (TextView) findViewById(R.id.puzzle_statement);
        puzzle_statement.setText(HtmlCompat.fromHtml(puzzle_statement_text, HtmlCompat.FROM_HTML_MODE_LEGACY));
        TextView path_length_statement = (TextView) findViewById(R.id.path_length_statement);
        path_length_statement.setText(path_length_statement_text);
    }

    private void addCountryVisited(Context context, String user_answer) {
        // Called each time user enters a correct answer
        LinearLayout countries_visited = (LinearLayout) findViewById(R.id.countries_visited);
        TextView new_country_visited = new TextView(context);
        new_country_visited.setText(user_answer);
        new_country_visited.setLayoutParams(root_country.getLayoutParams());
        new_country_visited.setTextColor(root_country.getCurrentTextColor());
        countries_visited.addView(new_country_visited);

        ImageView new_arrow = new ImageView(context);
        new_arrow.setImageDrawable(arrow.getDrawable());
        new_arrow.setScaleType(arrow.getScaleType());
        new_arrow.setLayoutParams(arrow.getLayoutParams());
        countries_visited.addView(new_arrow);
    }

    private void openGiveUpDialog(View v) {
        AlertDialog.Builder builder = new AlertDialog.Builder(SolvePuzzle.this);
        builder.setMessage(R.string.give_up_message);
        builder.setCancelable(true);
        builder.setPositiveButton("Main Menu",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Intent intent = new Intent(SolvePuzzle.this, MainActivity.class);
                        startActivity(intent);
                    }
                }
        );
        builder.setNegativeButton("Keep Going",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                }
        );
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void reachedDestinationCountry(Context context, Resources res) {
        // Update the UI when the user has correctly entered the last country before destination,
        // which means we've reached the destination (we don't ask the user to input the destination country)
        LinearLayout countries_visited = (LinearLayout) findViewById(R.id.countries_visited);
        TextView destination_country = new TextView(context);
        destination_country.setText(country_to);
        destination_country.setLayoutParams(root_country.getLayoutParams());
        destination_country.setTextColor(root_country.getCurrentTextColor());
        countries_visited.addView(destination_country);
        TextView congratulations = (TextView) findViewById(R.id.congratulations);
        congratulations.setText(res.getString(R.string.congratulations));

        Button buttonGiveUp = (Button) findViewById(R.id.button_give_up);
        buttonGiveUp.setVisibility(View.GONE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Always build the screen, including when Android recreates this activity
        // (savedInstanceState != null), otherwise the user gets a blank screen
        setContentView(R.layout.activity_solve_puzzle);
        SystemBars.setUp(this);

        Intent intent = getIntent();
        if (intent != null) {
            level = intent.getIntExtra(MainActivity.LEVEL, 0);
        }
        int path_length_lower = 3 + 2 * level;
        int path_length_upper = 4 + 2 * level;
        if (level == 2) {
            path_length_upper = 20;  // Not binding; current maximum in data is 9
        }

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setHomeButtonEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowHomeEnabled(true);

        final Resources res = getResources();
        countries = res.getStringArray(R.array.countries);
        congratulations_array = res.getStringArray(R.array.correct_guess_congratulations);

        final Context context = getApplicationContext();
        MySQLiteHelper mSQLiteHelper = new MySQLiteHelper(context);
        db = mSQLiteHelper.getReadableDatabase();

        ArrayList<String> restored_route = null;
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_FROM)) {
            // Same puzzle as before Android recreated us
            country_from_index = savedInstanceState.getInt(STATE_FROM);
            country_to_index = savedInstanceState.getInt(STATE_TO);
            restored_route = savedInstanceState.getStringArrayList(STATE_ROUTE);
        } else {
            Cursor c = getSummaryCursor(path_length_lower, path_length_upper, db);
            country_from_index = c.getInt(c.getColumnIndexOrThrow(MySQLiteHelper.COL_FROM));
            country_to_index = c.getInt(c.getColumnIndexOrThrow(MySQLiteHelper.COL_TO));
            c.close();
        }
        country_from = countries[country_from_index];
        country_to = countries[country_to_index];

        root_country = (TextView) findViewById(R.id.countries_visited_root);
        root_country.setText(country_from);
        arrow = (ImageView) findViewById(R.id.countries_visited_arrow);
        int id = getResources().getIdentifier("arrow", "drawable", getPackageName());
        arrow.setImageResource(id);

        Cursor c2 = getPathCursor(country_from_index, country_to_index, db);
        number_of_paths = c2.getCount();
        String[][] paths_tokenized = new String[number_of_paths][];
        int i = 0;
        while (c2.moveToNext()) {
            paths_tokenized[i] = c2.getString(c2.getColumnIndexOrThrow(MySQLiteHelper.COL_PATH)).split(";");
            i++;
        }
        c2.close();
        // Every shortest path between the same two countries has the same length
        path_length_inner = paths_tokenized[0].length;  // Number of countries the user has to input
        path_length = path_length_inner + 2;
        paths = new String[number_of_paths][path_length_inner];
        for (i = 0; i < number_of_paths; i++) {
            for (int j = 0; j < path_length_inner; j++) {
                paths[i][j] = countries[Integer.parseInt(paths_tokenized[i][j])];
            }
        }
        path_indicators = new boolean[number_of_paths];  // Paths with which current answer is consistent
        Arrays.fill(path_indicators, true);
        user_progress = 0;
        user_route.clear();

        setPuzzleStatement(res);

        Button buttonGiveUp = (Button) findViewById(R.id.button_give_up);
        buttonGiveUp.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openGiveUpDialog(v);
            }
        });

        country_input = (AutoCompleteTextView) findViewById(R.id.autocomplete_country);

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, countries);
        country_input.setAdapter(adapter);
        country_input.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                countryEntered(adapter.getItem(position).toString());
            }
        });

        if (restored_route != null) {
            // Put back the countries the user had already entered, without repeating
            // the toasts or the database updates
            for (String country : restored_route) {
                if (!moveTo(country)) {
                    break;  // Shouldn't happen: the route was valid when it was saved
                }
            }
        }
    }

    private void countryEntered(String user_answer) {
        final Context context = getApplicationContext();
        final Resources res = getResources();
        if (moveTo(user_answer)) {
            // TODO Put this in a fn, and call it for very last country seen
            ContentValues content = new ContentValues();
            content.put(MySQLiteHelper.COL_COUNTRY_NAME, user_answer);
            content.put(MySQLiteHelper.COL_COUNTRY_VISIT_COUNT, 1);  // TODO Get previous count and increment
            db.update(MySQLiteHelper.VISIT_COUNT_TABLE_NAME, content, MySQLiteHelper.COL_COUNTRY_NAME + " = ?", new String[]{user_answer});

            if (user_progress < path_length_inner) {
                String congratulations = congratulations_array[random.nextInt(congratulations_array.length)];
                Toast.makeText(context, congratulations, Toast.LENGTH_SHORT).show();
            } else {
                String greatGuide = res.getString(R.string.great_guide);
                Toast.makeText(context, greatGuide, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(context, "Sorry, " + user_answer + " won\'t help.", Toast.LENGTH_LONG).show();
        }
        country_input.setText("");
    }

    /**
     * Moves the user to country if it's the next country on a shortest path consistent with the
     * countries entered so far, and updates the route on screen. Returns whether it was.
     */
    private boolean moveTo(String country) {
        boolean correct = false;
        boolean[] new_path_indicators = new boolean[number_of_paths];  // Default to false
        for (int i = 0; i < number_of_paths; i++) {
            if (path_indicators[i] && paths[i][user_progress].equals(country)) {
                correct = true;
                new_path_indicators[i] = true;
            }
        }
        if (!correct) {
            return false;
        }
        user_progress++;
        user_route.add(country);
        path_indicators = new_path_indicators;
        addCountryVisited(getApplicationContext(), country);

        if (user_progress >= path_length_inner) {
            // Done solving puzzle: make country_input invisible; close keyboard
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(country_input.getWindowToken(), 0);
            country_input.setVisibility(View.GONE);

            reachedDestinationCountry(getApplicationContext(), getResources());

            LinearLayout buttons = (LinearLayout) findViewById(R.id.buttons);
            buttons.setVisibility(View.VISIBLE);
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putInt(STATE_FROM, country_from_index);
        savedInstanceState.putInt(STATE_TO, country_to_index);
        savedInstanceState.putStringArrayList(STATE_ROUTE, new ArrayList<>(user_route));
    }

    public void backToMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }

    public void newPuzzle(View view) {
        Intent intent = new Intent(this, SolvePuzzle.class);
        intent.putExtra(MainActivity.LEVEL, level);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.solve_puzzle, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }
}
