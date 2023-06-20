package com.gluecode.fpvdrone.server;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.log.FallbackMLog;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.codec.binary.Hex;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.annotation.Nullable;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class MySQLHelper {
  public static String DB_HOST;
  private static String DB_PORT;
  private static String DB_USERNAME;
  private static String DB_PASSWORD;
  private static String DB_SCHEMA;

  private static boolean loaded = false;
  private static boolean inFlight = false;
  private static Connection connection;

  private static ComboPooledDataSource pool;

  public static int nCalls = 0;

  public static void load() {
    if (!loaded) {
      Dotenv dotenv = Dotenv.load();

      DB_HOST = dotenv.get("DB_HOST");
      DB_PORT = dotenv.get("DB_PORT");
      DB_USERNAME = dotenv.get("DB_USERNAME");
      DB_PASSWORD = dotenv.get("DB_PASSWORD");
      DB_SCHEMA = dotenv.get("DB_SCHEMA");

      Main.LOGGER.info("DB_HOST: " + DB_HOST);
      Main.LOGGER.info("DB_USERNAME: " + DB_USERNAME);
      Main.LOGGER.info("DB_SCHEMA: " + DB_SCHEMA);

      try {
//        Class.forName("com.mysql.cj.jdbc.Driver");
//        connection = getConnection();
        loaded = initPool();
        if (!loaded) {
          Main.shutdown("Unable to load pool.");
        }
      } catch (Exception ex) {
        // handle the error
        Main.LOGGER.info(ex);
        ex.printStackTrace();
      }
    }
  }

  public static void close() {
//    if (connection != null && !inFlight) {
//      try {
//        Main.LOGGER.info("Closing MySQL connection...");
//        connection.close();
//        connection = null;
//      } catch (Exception e) {
//      }
//    }

    if (pool != null) {
      try {
        Main.LOGGER.info("Closing MySQL pool...");
        pool.close();
        pool = null;
      } catch (Exception e) {
        Main.LOGGER.error(e);
      }
    }
  }

  /*
  return true if success
  * */
  public static boolean initPool() {
    try {
      Properties p = new Properties(System.getProperties());
      p.put("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
      p.put("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "OFF"); // Off or any other level
      System.setProperties(p);

      pool = new ComboPooledDataSource();
      pool.setDriverClass("com.mysql.cj.jdbc.Driver"); //loads the jdbc driver
      String url = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_SCHEMA;
      url += "?serverTimezone=UTC";
      pool.setJdbcUrl(url);
      pool.setUser(DB_USERNAME);
      pool.setPassword(DB_PASSWORD);

// the settings below are optional -- c3p0 can work with defaults
      pool.setMinPoolSize(5);
      pool.setAcquireIncrement(1);
      pool.setMaxPoolSize(5);

// The DataSource cpds is now a fully configured and usable pooled DataSource
      return true;
    } catch (Exception e) {
      Main.LOGGER.error(e.getMessage());
      return false;
    }
  }

//  public static Connection getConnection() {
//    try {
//      String url = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_SCHEMA + "?autoReconnect=true";
//      Main.LOGGER.info(url);
//      return DriverManager.getConnection(
//      url,
//      DB_USERNAME,
//      DB_PASSWORD
//      );
//    } catch (SQLException ex) {
//      Main.LOGGER.error("SQLException: " + ex.getMessage());
//      Main.LOGGER.error("SQLState: " + ex.getSQLState());
//      Main.LOGGER.error("VendorError: " + ex.getErrorCode());
//    }
//    return null;
//  }

  public static CompletableFuture<Boolean> checkTableExists(String tableName) {
    String sql = String.format("SELECT * FROM information_schema.TABLES " +
    "WHERE TABLE_SCHEMA = \"%s\" " +
    "AND TABLE_NAME = \"%s\";", DB_SCHEMA, tableName);
    try {
      JSONArray data = await(prepareAndExecute(sql, null));
      return completedFuture(data != null && data.size() != 0);
    } catch (Exception e) {
      return completedFuture(false);
    }
  }

  public static CompletableFuture<JSONArray> prepareAndExecute(String sql, @Nullable Preparer setter) throws Exception {
    nCalls++;
    Connection connection = null;
    try {
      connection = pool.getConnection();
      PreparedStatement statement = connection.prepareStatement(sql);
      if (setter != null) {
        setter.accept(statement);
      }
      if (DB_HOST.equals("localhost")) {
        Main.LOGGER.info(statement);
      }
      JSONArray rows = await(CompletableFuture.supplyAsync(() -> {
        JSONArray array = null;
        try {
          if (statement.execute()) {
            // SELECT
            ResultSet result = statement.getResultSet();
            if (result != null) {
              array = convert(result);
            }
          } else {
            // CREATE, UPDATE, INSERT
            array = new JSONArray();
          }
        } catch (SQLException e) {
          Main.LOGGER.error(e.getMessage());
        }
        return array;
      }));
      return completedFuture(rows);
    } catch (SQLException e) {
      Main.LOGGER.error(e.getMessage());
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e2) {
          Main.LOGGER.error(e2.getMessage());
        }
      }
    }
    throw new Exception("SQL query failed.");
  }

//  public static @Nullable
//  PreparedStatement prepare(String sql) {
//    PreparedStatement statement = null;
//
//    try {
//      statement = connection.prepareStatement(sql);
//    } catch (SQLException ex) {
//      Main.LOGGER.info("SQLException: " + ex.getMessage());
//      Main.LOGGER.info("SQLState: " + ex.getSQLState());
//      Main.LOGGER.info("VendorError: " + ex.getErrorCode());
//      if (statement != null) {
//        try {
//          statement.close();
//        } catch (SQLException sqlEx) {
//        } // ignore
//      }
//    }
//    return statement;
//  }

  /*
    Awaited value will not be null.
  * */
//  public static CompletableFuture<JSONArray> execute(@Nullable PreparedStatement statement) throws Exception {
//    nCalls++;
//    inFlight = true;
//    JSONArray res = await(CompletableFuture.supplyAsync(() -> executeSync(statement)));
//    if (res == null) {
//      throw new Exception("SQL statement failed.");
//    }
//    return completedFuture(res);
//  }

//  public static JSONArray executeSync(@Nullable PreparedStatement statement) {
//    if (statement == null) return null;
//
//    if (DB_HOST.equals("localhost")) {
//      Main.LOGGER.info(statement);
//    }
//
//    ResultSet result = null;
//    JSONArray array = null;
//    try {
//      if (statement.execute()) {
//        // SELECT
//        inFlight = false;
//        result = statement.getResultSet();
//        if (result != null) {
//          array = convert(result);
//        }
//      } else {
//        // CREATE, UPDATE, INSERT
//        array = new JSONArray();
//      }
//    } catch (SQLException ex) {
//      Main.LOGGER.error("SQLException: " + ex.getMessage());
//      Main.LOGGER.error("SQLState: " + ex.getSQLState());
//      Main.LOGGER.error("VendorError: " + ex.getErrorCode());
//    } finally {
//      if (statement != null) {
//        try {
//          statement.close();
//        } catch (SQLException sqlEx) {
//        } // ignore
//      }
//
//      if (result != null) {
//        try {
//          result.close();
//        } catch (SQLException sqlEx) {
//        } // ignore
//      }
//    }
//
//    return array;
//  }

  public static JSONArray convert(ResultSet rs) throws SQLException {
    JSONArray json = new JSONArray();
    ResultSetMetaData rsmd = rs.getMetaData();

    while (rs.next()) {
      int numColumns = rsmd.getColumnCount();
      JSONObject obj = new JSONObject();

      for (int i = 1; i < numColumns + 1; i++) {
        String column_name = rsmd.getColumnName(i);

        if (rsmd.getColumnType(i) == java.sql.Types.ARRAY) {
          obj.put(column_name, rs.getArray(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.BIGINT) {
          obj.put(column_name, rs.getInt(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.BOOLEAN) {
          obj.put(column_name, rs.getBoolean(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.BINARY) {
          Blob blob = rs.getBlob(column_name);
          byte[] bytes = blob.getBytes(1, 16);
          String id = Hex.encodeHexString(bytes).toUpperCase();
          obj.put(column_name, id);
        } else if (rsmd.getColumnType(i) == java.sql.Types.BLOB) {
          obj.put(column_name, rs.getBlob(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.DOUBLE) {
          obj.put(column_name, rs.getDouble(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.FLOAT) {
          obj.put(column_name, rs.getFloat(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.INTEGER) {
          obj.put(column_name, rs.getInt(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.NVARCHAR) {
          obj.put(column_name, rs.getNString(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.VARCHAR) {
          obj.put(column_name, rs.getString(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.TINYINT) {
          obj.put(column_name, rs.getInt(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.SMALLINT) {
          obj.put(column_name, rs.getInt(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.DATE) {
          obj.put(column_name, rs.getDate(column_name));
        } else if (rsmd.getColumnType(i) == java.sql.Types.TIMESTAMP) {
          obj.put(column_name, rs.getTimestamp(column_name));
        } else {
          obj.put(column_name, rs.getObject(column_name));
        }
      }

      json.add(obj);
    }

    return json;
  }

  public static String newId() {
    return UuidCreator.getTimeOrdered().toString().replaceAll("-", "").toUpperCase();
  }

  /*
  Only allows standard keyboard characters.
  No spaces allowed.
  No backslash allowed.
  Max length 32767
  * */
  public static String sanitizeKeyboard(String value) {
    String v = value.replaceAll("[^!-~]", "").replaceAll("\\\\", "");
    if (v.length() > 32767) {
      v = v.substring(0, 32767);
    }
    return v;
  }
}
