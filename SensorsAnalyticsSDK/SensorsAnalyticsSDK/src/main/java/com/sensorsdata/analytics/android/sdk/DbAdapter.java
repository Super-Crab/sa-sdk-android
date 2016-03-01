package com.sensorsdata.analytics.android.sdk;

import com.sensorsdata.analytics.android.sdk.exceptions.QueueLimitExceededException;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/* package */ class DbAdapter {

  private static final String LOGTAG = "SA.DBAdapter";

  public enum Table {
    EVENTS("events");

    Table(String name) {
      mTableName = name;
    }

    public String getName() {
      return mTableName;
    }

    private final String mTableName;
  }

  public static final String KEY_DATA = "data";
  public static final String KEY_CREATED_AT = "created_at";

  public static final int DB_UPDATE_ERROR = -1;
  public static final int DB_OUT_OF_MEMORY_ERROR = -2;
  public static final int DB_UNDEFINED_CODE = -3;

  private static final String DATABASE_NAME = "sensorsdata";

  private static final int DATABASE_VERSION = 4;

  private static final String CREATE_EVENTS_TABLE =
      "CREATE TABLE " + Table.EVENTS.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
          KEY_DATA + " STRING NOT NULL, " +
          KEY_CREATED_AT + " INTEGER NOT NULL);";
  private static final String EVENTS_TIME_INDEX =
      "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.EVENTS.getName() +
          " (" + KEY_CREATED_AT + ");";

  private final DatabaseHelper mDb;

  private static class DatabaseHelper extends SQLiteOpenHelper {

    DatabaseHelper(Context context, String dbName) {
      super(context, dbName, null, DATABASE_VERSION);
      mDatabaseFile = context.getDatabasePath(dbName);
    }

    /**
     * Completely deletes the DB file from the file system.
     */
    public void deleteDatabase() {
      close();
      mDatabaseFile.delete();
    }

    @Override public void onCreate(SQLiteDatabase db) {
      Log.d(LOGTAG, "Creating a new SensorsData events DB");

      db.execSQL(CREATE_EVENTS_TABLE);
      db.execSQL(EVENTS_TIME_INDEX);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      Log.d(LOGTAG, "Upgrading app, replacing SensorsData events DB");

      db.execSQL("DROP TABLE IF EXISTS " + Table.EVENTS.getName());
      db.execSQL(CREATE_EVENTS_TABLE);
      db.execSQL(EVENTS_TIME_INDEX);
    }

    public boolean belowMemThreshold() {
      if (mDatabaseFile.exists()) {
        return Math.max(
            mDatabaseFile.getUsableSpace(),
            32 * 1024 * 1024 // 32MB
        ) >= mDatabaseFile.length();
      }
      return true;
    }

    private final File mDatabaseFile;
  }

  public DbAdapter(Context context) {
    this(context, DATABASE_NAME);
  }

  public DbAdapter(Context context, String dbName) {
    mDb = new DatabaseHelper(context, dbName);
  }

  /**
   * Adds a JSON string representing an event with properties or a person record
   * to the SQLiteDatabase.
   *
   * @param j     the JSON to record
   * @param table the table to insert into, either "events" or "people"
   *
   * @return the number of rows in the table, or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
   * on failure
   */
  public int addJSON(JSONObject j, Table table) throws QueueLimitExceededException {
    // we are aware of the race condition here, but what can we do..?
    if (!mDb.belowMemThreshold()) {
      throw new QueueLimitExceededException("There is not enough space left on the device to "
          + "store SensorsData data, so data was discarded");
    }

    final String tableName = table.getName();

    Cursor c = null;
    int count = DB_UPDATE_ERROR;

    try {
      final SQLiteDatabase db = mDb.getWritableDatabase();

      final ContentValues cv = new ContentValues();
      cv.put(KEY_DATA, j.toString());
      cv.put(KEY_CREATED_AT, System.currentTimeMillis());
      db.insert(tableName, null, cv);

      c = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
      c.moveToFirst();
      count = c.getInt(0);
    } catch (final SQLiteException e) {
      Log.e(LOGTAG, "Could not add data to table " + tableName + ". Re-initializing database.", e);

      // We assume that in general, the results of a SQL exception are
      // unrecoverable, and could be associated with an oversized or
      // otherwise unusable DB. Better to bomb it and get back on track
      // than to leave it junked up (and maybe filling up the disk.)
      if (c != null) {
        c.close();
        c = null;
      }
      mDb.deleteDatabase();
    } finally {
      if (c != null) {
        c.close();
      }
      mDb.close();
    }
    return count;
  }

  /**
   * Removes events with an _id <= last_id from table
   *
   * @param last_id the last id to delete
   * @param table   the table to remove events from
   *
   * @return the number of rows in the table
   */
  public int cleanupEvents(String last_id, Table table) {
    final String tableName = table.getName();

    int count = DB_UPDATE_ERROR;

    try {
      final SQLiteDatabase db = mDb.getWritableDatabase();
      db.delete(tableName, "_id <= " + last_id, null);

      Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
      c.moveToFirst();
      count = c.getInt(0);
    } catch (final SQLiteException e) {
      Log.e(LOGTAG,
          "Could not clean sent records from " + tableName + ". Re-initializing database.", e);
      mDb.deleteDatabase();
    } finally {
      mDb.close();
    }
    return count;
  }

  /**
   * Removes events before time.
   *
   * @param time  the unix epoch in milliseconds to remove events before
   * @param table the table to remove events from
   *
   * @return the number of rows in the table
   */
  public int cleanupEvents(long time, Table table) {
    final String tableName = table.getName();

    int count = DB_UPDATE_ERROR;

    try {
      final SQLiteDatabase db = mDb.getWritableDatabase();
      db.delete(tableName, KEY_CREATED_AT + " <= " + time, null);

      Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
      c.moveToFirst();
      count = c.getInt(0);
    } catch (final SQLiteException e) {
      Log.e(LOGTAG, "Could not clean timed-out SensorsData records from " + tableName
          + ". Re-initializing database.", e);
      mDb.deleteDatabase();
    } finally {
      mDb.close();
    }

    return count;
  }

  public void deleteDB() {
    mDb.deleteDatabase();
  }

  public String[] generateDataString(Table table, int limit) {
    Cursor c = null;
    String data = null;
    String last_id = null;
    final String tableName = table.getName();

    try {
      final SQLiteDatabase db = mDb.getReadableDatabase();
      c = db.rawQuery("SELECT * FROM " + tableName +
          " ORDER BY " + KEY_CREATED_AT + " ASC LIMIT " + String.valueOf(limit), null);
      final JSONArray arr = new JSONArray();

      while (c.moveToNext()) {
        if (c.isLast()) {
          last_id = c.getString(c.getColumnIndex("_id"));
        }
        try {
          final JSONObject j = new JSONObject(c.getString(c.getColumnIndex(KEY_DATA)));
          arr.put(j);
        } catch (final JSONException e) {
          // Ignore this object
        }
      }

      if (arr.length() > 0) {
        data = arr.toString();
      }
    } catch (final SQLiteException e) {
      Log.e(LOGTAG, "Could not pull records for SensorsData out of database " + tableName
          + ". Waiting to send.", e);
      last_id = null;
      data = null;
    } finally {
      mDb.close();
      if (c != null) {
        c.close();
      }
    }

    if (last_id != null && data != null) {
      final String[] ret = {last_id, data};
      return ret;
    }
    return null;
  }

}
