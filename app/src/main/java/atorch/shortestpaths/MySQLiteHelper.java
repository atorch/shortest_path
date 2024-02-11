package atorch.shortestpaths;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class MySQLiteHelper extends SQLiteOpenHelper {

    // Database with two tables: summary and paths
    public static final String SUMMARY_TABLE_NAME = "summary";
    public static final String PATH_TABLE_NAME = "paths";
    public static final String VISIT_COUNT_TABLE_NAME = "visit_counts";

    // Column names
    public static final String COL_FROM = "country_from";
    public static final String COL_TO = "country_to";
    public static final String COL_PATH_LENGTH = "path_length";
    public static final String COL_PATH = "path";
    public static final String COL_COUNTRY_NAME = "country_name";
    public static final String COL_COUNTRY_VISIT_COUNT = "n_times_visited";

    // Gives the length of the shortest path(s) between COL_FROM and COL_TO
    private static final String SQL_CREATE_SUMMARY = "CREATE TABLE " + SUMMARY_TABLE_NAME +
            " (" + COL_FROM + " INT, " + COL_TO + " INT, " + COL_PATH_LENGTH + " INT)";
    private static final String SQL_DELETE_SUMMARY = "DROP TABLE IF EXISTS " + SUMMARY_TABLE_NAME;

    // One entry for each shortest path between COL_FROM and COL_TO
    // There may exist multiple shortest paths with the same length, or there could be exactly one
    private static final String SQL_CREATE_PATH = "CREATE TABLE " + PATH_TABLE_NAME +
            " (" + COL_FROM + " INT, " + COL_TO + " INT, " + COL_PATH + " TEXT)";
    private static final String SQL_DELETE_PATH = "DROP TABLE IF EXISTS " + PATH_TABLE_NAME;

    // This tables tracks which counties have been visited
    private static final String SQL_CREATE_VISIT_COUNT = "CREATE TABLE " + VISIT_COUNT_TABLE_NAME +
            " (" + COL_COUNTRY_NAME + " TEXT, " + COL_COUNTRY_VISIT_COUNT + " INT)";
    private static final String SQL_DELETE_VISIT_COUNT = "DROP TABLE IF EXISTS " + VISIT_COUNT_TABLE_NAME;

    public static final String SQL_COUNT_COUNTRIES_VISITED = "SELECT COUNT(" + COL_COUNTRY_NAME + ") FROM " +
             VISIT_COUNT_TABLE_NAME + " WHERE " + COL_COUNTRY_VISIT_COUNT + " > 0";

    // public static final String SQL_UPDATE_COUNTIES_VISITED = "UPDATE " + VISIT_COUNT_TABLE_NAME +
    //         " SET " + COL_COUNTRY_VISIT_COUNT + " = " + COL_COUNTRY_VISIT_COUNT + " + 1 WHERE " + COL_COUNTRY_NAME + " = '%s'";

    public static final int DATABASE_VERSION = 3;  // Increment the version whenever you change the schema
    public static final String DATABASE_NAME = "atorch_shortestpath_db";

    public MySQLiteHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_SUMMARY);
        db.execSQL(SQL_CREATE_PATH);
        db.execSQL(SQL_CREATE_VISIT_COUNT);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Delete and recreate
        db.execSQL(SQL_DELETE_SUMMARY);
        db.execSQL(SQL_DELETE_PATH);
        db.execSQL(SQL_DELETE_VISIT_COUNT);
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

}
