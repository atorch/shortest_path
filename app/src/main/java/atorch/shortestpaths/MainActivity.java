package atorch.shortestpaths;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private static final int POINTS_LEVEL_1 = 8;
    private static final int POINTS_LEVEL_2 = 24;

    final boolean ENABLE_DEBUG = true;
    final String TAG = "debug_tag";
	
	public final static String LEVEL = "atorch.shortestpath.LEVEL";
	
	public static Context context;
	public static MySQLiteHelper mSQLiteHelper;
	public static SQLiteDatabase db;
	
	public static boolean done_writing_summary = false;
	public static boolean done_writing_paths = false;
	
	public int points;
	
	private class WriteDatabaseTask extends AsyncTask<String, Void, String> {
		protected String doInBackground(String... table_name) {
			Resources res = getResources();
			if(table_name[0] == MySQLiteHelper.SUMMARY_TABLE_NAME) {
				InputStream inputStream = res.openRawResource(R.raw.graph_paths_summary);
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
				
				int from, to, path_length;		
				db.beginTransaction();
			    try {
			        String line = reader.readLine();  // Skip header in first line
			        while ((line = reader.readLine()) != null) {
			             String[] RowData = line.split(",");
			             
			             from = Integer.parseInt(RowData[0]);
			             to = Integer.parseInt(RowData[1]);
			             path_length = Integer.parseInt(RowData[2]);
			             
			             ContentValues values = new ContentValues();
			             values.put(MySQLiteHelper.COL_FROM, from);
			             values.put(MySQLiteHelper.COL_TO, to);
			             values.put(MySQLiteHelper.COL_PATH_LENGTH, path_length);
			             
			             db.insert(MySQLiteHelper.SUMMARY_TABLE_NAME, null, values);
			        }
			    }
			    catch (IOException ex) {
			    }
			    finally {
			        try {
			            inputStream.close();
			        }
			        catch (IOException e) {
			        }
			    }
			    db.setTransactionSuccessful();
			    db.endTransaction();
			    
			    int rows_summary = (int)DatabaseUtils.queryNumEntries(db, MySQLiteHelper.SUMMARY_TABLE_NAME);
			    return("wrote " + rows_summary + " rows to summary table");
			}
			
			if(table_name[0] == MySQLiteHelper.PATH_TABLE_NAME) {
				InputStream inputStream = res.openRawResource(R.raw.graph_paths_subset);
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
				
				int from, to;
				String path;		
				db.beginTransaction();
			    try {
			        String line = reader.readLine();  // Skip header in first line
			        while ((line = reader.readLine()) != null) {
			             String[] RowData = line.split(",");
			             
			             from = Integer.parseInt(RowData[0]);
			             to = Integer.parseInt(RowData[1]);
			             path = RowData[2];
			             
			             ContentValues values = new ContentValues();
			             values.put(MySQLiteHelper.COL_FROM, from);
			             values.put(MySQLiteHelper.COL_TO, to);
			             values.put(MySQLiteHelper.COL_PATH, path);
			             
			             db.insert(MySQLiteHelper.PATH_TABLE_NAME, null, values);
			        }
			    }
			    catch (IOException ex) {
			    }
			    finally {
			        try {
			            inputStream.close();
			        }
			        catch (IOException e) {
			        }
			    }
			    db.setTransactionSuccessful();
			    db.endTransaction();
			    
			    int rows_paths = (int)DatabaseUtils.queryNumEntries(db, MySQLiteHelper.PATH_TABLE_NAME);
			    return ("wrote " + rows_paths + " rows to path table");
			}
			return("unknown table name");
		}
		
		protected void onPostExecute(String returned_by_doInBackground) {
			if(returned_by_doInBackground.contains("path")) {
				done_writing_paths = true;
			} else if (returned_by_doInBackground.contains("summary")) {
				done_writing_summary = true;
			}
			if(done_writing_summary && done_writing_paths) {
				removeBarAndAddUI();
			}
			Toast.makeText(context, returned_by_doInBackground, Toast.LENGTH_SHORT).show();
		}
	}
	
	@SuppressLint("StringFormatMatches")
	public void startPuzzle(View view) {
		final Resources res = getResources();
		Intent intent = new Intent(this, SolvePuzzle.class);
		int level = 0;
		int view_id = view.getId();
		if (view_id == R.id.button_1) {
			level = 1;
		} else if (view_id == R.id.button_2) {
			level = 2;
		}
		intent.putExtra(LEVEL, level);
		if(level == 0) {
			startActivity(intent);
		} else if(level == 1) {
			if(points >= POINTS_LEVEL_1) {
				startActivity(intent);
			} else {
                String formatString = res.getString(R.string.level_1_locked);
				Toast.makeText(context, String.format(formatString, POINTS_LEVEL_1), Toast.LENGTH_LONG).show();
			}
		} else {
			if(points >= POINTS_LEVEL_2) {
				startActivity(intent);
			} else {
                String formatString = res.getString(R.string.level_2_locked);
				Toast.makeText(context, String.format(formatString, POINTS_LEVEL_2), Toast.LENGTH_LONG).show();
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
				
		context = getApplicationContext();		
		mSQLiteHelper = new MySQLiteHelper(context);
		db = mSQLiteHelper.getWritableDatabase();
		
		int rows_summary = (int)DatabaseUtils.queryNumEntries(db, MySQLiteHelper.SUMMARY_TABLE_NAME);
		if (rows_summary == 0) {
			new WriteDatabaseTask().execute(MySQLiteHelper.SUMMARY_TABLE_NAME);
		} else {
			done_writing_summary = true;
		}
		
		int rows_paths = (int)DatabaseUtils.queryNumEntries(db, MySQLiteHelper.PATH_TABLE_NAME);
		if (rows_paths == 0) {
			new WriteDatabaseTask().execute(MySQLiteHelper.PATH_TABLE_NAME);
		} else {
			done_writing_paths = true;
		}
		
		if(done_writing_summary && done_writing_paths) {
			removeBarAndAddUI();
		}
	}
	
	private void updatePoints() {
		final Resources res = getResources();
		SharedPreferences sharedPref = getSharedPreferences("atorch.shortestpaths.data", Context.MODE_PRIVATE);          			
		points = sharedPref.getInt(getString(R.string.points_counter), 0);
		TextView path_length_statement = (TextView) findViewById(R.id.points_statement);
		if(points == 0) {
			path_length_statement.setText(res.getString(R.string.points_statement_zero));
		} else {
			path_length_statement.setText(Html.fromHtml(res.getQuantityString(R.plurals.points_statement, points, points)));
		}
	}
	
	private void removeBarAndAddUI() {
		LinearLayout barLayout = (LinearLayout)findViewById(R.id.progress_bar);
		barLayout.setVisibility(View.GONE);
		
		updatePoints();
		
		LinearLayout ui = (LinearLayout)findViewById(R.id.main_ui);
		ui.setVisibility(View.VISIBLE);
	}

	@Override
	public void onResume() {
		super.onResume();
		updatePoints();  // In case user hits back arrow after solving a puzzle
	}

}
