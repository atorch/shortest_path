package atorch.shortestpaths;

import android.content.ContentValues;
import java.util.Arrays;
import java.util.Random;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.Html;
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

public class SolvePuzzle extends AppCompatActivity {

    public final static String COUNTRY_TO = "atorch.shortestpaths.COUNTRY_TO";

    static TextView root_country;
    static ImageView arrow;

    public static int level;

    static String[] congratulations_array;

    static String[] countries;
    static int country_from_index;
    static int country_to_index;
    static String country_from;
    static String country_to;

    static int path_length;
    static int path_length_inner;  // Equal to path_length - 2, i.e. number of countries user has to input
    static int number_of_paths;
    static String paths[][];  // Paths[i] is an array of country names
    static boolean path_indicators[];  // When user's guess is consistent with paths[i], path_indicators[i] will be true
    static int user_progress;  // How far along path user has traveled

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
        Cursor c = db.query(MySQLiteHelper.PATH_TABLE_NAME, projection_path, where_from_to, from_to_conditions, null, null, null);
        return c;
    }

    private void setPuzzleStatement(Resources res) {
        String puzzle_statement_text = String.format(res.getString(R.string.puzzle_statement_format_string), country_from, country_to);
        String path_length_statement_text = res.getQuantityString(R.plurals.path_length_statement, path_length_inner, path_length_inner);
        TextView puzzle_statement = (TextView) findViewById(R.id.puzzle_statement);
        puzzle_statement.setText(Html.fromHtml(puzzle_statement_text));
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

    private void addDestinationCountry(Context context, Resources res) {
        // Called when the user has correctly entered the last country before destination
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
        if (savedInstanceState != null) {
            // TODO This is most likely wrong
            country_to = savedInstanceState.getString(COUNTRY_TO);
        } else {
            setContentView(R.layout.activity_solve_puzzle);

            user_progress = 0;

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
            SQLiteDatabase db = mSQLiteHelper.getReadableDatabase();

            Cursor c = getSummaryCursor(path_length_lower, path_length_upper, db);
            country_from_index = c.getInt(c.getColumnIndexOrThrow(MySQLiteHelper.COL_FROM));
            country_to_index = c.getInt(c.getColumnIndexOrThrow(MySQLiteHelper.COL_TO));
            country_from = countries[country_from_index];
            country_to = countries[country_to_index];
            path_length = c.getInt(c.getColumnIndexOrThrow(MySQLiteHelper.COL_PATH_LENGTH));
            path_length_inner = path_length - 2;  // Number of countries the user has to input
            c.close();

            root_country = (TextView) findViewById(R.id.countries_visited_root);
            root_country.setText(country_from);
            arrow = (ImageView) findViewById(R.id.countries_visited_arrow);
            int id = getResources().getIdentifier("arrow", "drawable", getPackageName());
            arrow.setImageResource(id);

            Cursor c2 = getPathCursor(country_from_index, country_to_index, db);
            number_of_paths = c2.getCount();
            paths = new String[number_of_paths][path_length_inner];
            path_indicators = new boolean[number_of_paths];  // Paths with which current answer is consistent
            Arrays.fill(path_indicators, true);
            String[] current_path_tokenized = new String[path_length_inner];
            int i = 0;
            String current_path;
            while (c2.moveToNext()) {
                current_path = c2.getString(c2.getColumnIndexOrThrow(MySQLiteHelper.COL_PATH));
                current_path_tokenized = current_path.split(";");
                for (int j = 0; j < path_length_inner; j++)
                    paths[i][j] = countries[Integer.parseInt(current_path_tokenized[j])];
                i++;
            }
            c2.close();

            setPuzzleStatement(res);

            Button buttonGiveUp = (Button) findViewById(R.id.button_give_up);
            buttonGiveUp.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    openGiveUpDialog(v);
                }
            });

            final AutoCompleteTextView country_input = (AutoCompleteTextView) findViewById(R.id.autocomplete_country);

            final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, countries);
            country_input.setAdapter(adapter);
            country_input.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    String user_answer = adapter.getItem(position).toString();
                    boolean user_answer_is_correct = false;
                    boolean new_path_indicators[] = new boolean[number_of_paths];  // Default to false
                    for (int i = 0; i < number_of_paths; i++) {
                        if (path_indicators[i] && paths[i][user_progress] == user_answer) {
                            user_answer_is_correct = true;
                            new_path_indicators[i] = true;
                        }
                    }
                    if (user_answer_is_correct) {
                        user_progress++;
                        path_indicators = new_path_indicators;
                        addCountryVisited(context, user_answer);
                        if (user_progress < path_length_inner) {
                            String congratulations = congratulations_array[random.nextInt(congratulations_array.length)];
                            Toast.makeText(context, congratulations, Toast.LENGTH_SHORT).show();
                        } else {
                            // Done solving puzzle: make country_input invisible; close keyboard
                            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(country_input.getWindowToken(), 0);
                            country_input.setVisibility(View.GONE);

                            addDestinationCountry(context, res);

                            // TODO Update reward string
                            String reward = res.getQuantityString(R.plurals.reward, 0, 0);
                            // Toast.makeText(context, reward, Toast.LENGTH_LONG).show();

                            // TODO Update DB
                            // TODO First pass: tell DB we visited the final country
                            // TODO Next ,tell DB we visited _every_ country along the path
                            // String sqlUpdate = MySQLiteHelper.SQL_UPDATE_COUNTIES_VISITED;
                            // Toast.makeText(context, sqlUpdate, Toast.LENGTH_LONG).show();
                            // db.execSQL();

                            ContentValues content = new ContentValues();
                            content.put(MySQLiteHelper.COL_COUNTRY_NAME, user_answer);
                            content.put(MySQLiteHelper.COL_COUNTRY_VISIT_COUNT, 1);  // TODO Get previous count and increment
                            int rowsUpdated;
                            rowsUpdated = db.update(MySQLiteHelper.VISIT_COUNT_TABLE_NAME, content, MySQLiteHelper.COL_COUNTRY_NAME + " = ?", new String[]{user_answer});

                            Toast.makeText(context, Integer.toString(rowsUpdated) + " " + user_answer, Toast.LENGTH_LONG).show();

                            LinearLayout buttons = (LinearLayout) findViewById(R.id.buttons);
                            buttons.setVisibility(View.VISIBLE);
                        }
                    } else {
                        Toast.makeText(context, "Sorry, " + user_answer + " won\'t help.", Toast.LENGTH_LONG).show();
                    }
                    country_input.setText("");
                }
            });
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);  // TODO Am I using this?
        savedInstanceState.putString(COUNTRY_TO, country_to);
    }

    public void backToMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }

    public void newPuzzle(View view) {
        Intent intent = new Intent(this, SolvePuzzle.class);
        intent.putExtra(MainActivity.LEVEL, SolvePuzzle.level);
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
