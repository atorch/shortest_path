package atorch.shortestpaths;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class MySQLiteHelper extends SQLiteOpenHelper {
	
	// Database with two tables: summary and paths
	public static final String SUMMARY_TABLE_NAME = "summary";
	public static final String PATH_TABLE_NAME = "paths";
	
	// Column names
	public static final String COL_FROM = "country_from";
	public static final String COL_TO = "country_to";
	public static final String COL_NUMBER_OF_PATHS = "number_of_paths";
	public static final String COL_PATH_LENGTH = "path_length";
	public static final String COL_PATH = "path";
	
	// Describes shortest paths between COL_FROM and COL_TO: how many are there and what is their length?
	private static final String SQL_CREATE_SUMMARY = "CREATE TABLE " + SUMMARY_TABLE_NAME + 
			" (" + COL_FROM + " INT, " + COL_TO + " INT, " + COL_PATH_LENGTH + " INT)";
	private static final String SQL_DELETE_SUMMARY = "DROP TABLE IF EXISTS " + SUMMARY_TABLE_NAME;
	
	// One entry for each shortest path between COL_FROM and COL_TO
	private static final String SQL_CREATE_PATH = "CREATE TABLE " + PATH_TABLE_NAME + 
			" (" + COL_FROM + " INT, " + COL_TO + " INT, " + COL_PATH + " TEXT)";
	private static final String SQL_DELETE_PATH = "DROP TABLE IF EXISTS " + PATH_TABLE_NAME;

    public static final int DATABASE_VERSION = 2;  // Increment the version whenever you change the schema
    public static final String DATABASE_NAME = "atorch_shortestpath_db";
    
	public MySQLiteHelper(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(SQL_CREATE_SUMMARY);
		db.execSQL(SQL_CREATE_PATH);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// Delete and recreate
		db.execSQL(SQL_DELETE_SUMMARY);
		db.execSQL(SQL_DELETE_PATH);
        onCreate(db);		
	}
	
	@Override
	public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

}
