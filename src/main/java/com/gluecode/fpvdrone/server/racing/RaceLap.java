package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

/*
Represents a completion of a lap.
Used for leaderboards.
* */
public class RaceLap {
  public String raceLapId;
  public String raceTrackId;
  public String userId;
  public int millis;
  public String version;
  public JSONObject data;
  public Timestamp dateCreated;

  private RaceLap() {}

  public static RaceLap parseFromDB(JSONObject row) {
    RaceLap lap = new RaceLap();
    lap.raceLapId = (String) row.get("raceLapId");
    lap.raceTrackId = (String) row.get("raceTrackId");
    lap.userId = (String) row.get("userId");
    lap.millis = (int) row.get("millis");
    lap.version = (String) row.get("version");
    lap.data = (JSONObject) JSONValue.parse((String) row.get("data"));
    lap.dateCreated = (Timestamp) row.get("dateCreated");
    return lap;
  }

  public static void createTable() throws Exception {
    String sql = "CREATE TABLE IF NOT EXISTS RaceLap (\n" +
    "`raceLapId` BINARY(16) NOT NULL,\n" +
    "`raceTrackId` BINARY(16) NOT NULL,\n" +
    "`userId` BINARY(16) NOT NULL,\n" +
    "`millis` MEDIUMINT UNSIGNED NOT NULL,\n" +
    "`version` VARCHAR(8) NOT NULL,\n" +
    "`data` JSON NOT NULL,\n" +
    "`dateCreated` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
    "PRIMARY KEY (`raceLapId`),\n" +
    "INDEX `RACETRACKIDINDEX` (`raceTrackId` ASC),\n" + // search by track
    "INDEX `USERIDINDEX` (`userId` ASC),\n" + // search by user
    "INDEX `MILLISINDEX` (`millis` ASC),\n" + // sorting times
    "INDEX `VERSIONINDEX` (`version` ASC)\n" + // only get current version
    ");";
    MySQLHelper.prepareAndExecute(sql, null).join();
  }

  public static CompletableFuture<Void> truncate() throws Exception {
    if (!MySQLHelper.DB_HOST.equals("localhost")) return completedFuture(null);
    String sql = "TRUNCATE TABLE RaceLap;";
    await(MySQLHelper.prepareAndExecute(sql, null));
    return completedFuture(null);
  }

  public static CompletableFuture<ArrayList<RaceLap>> selectAll() throws Exception {
    String sql = "SELECT * FROM RaceLap;";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, null));
    ArrayList<RaceLap> result = new ArrayList<>();
    for (Object row : rows) {
      result.add(parseFromDB((JSONObject) row));
    }
    return completedFuture(result);
  }

  public static CompletableFuture<ArrayList<RaceLap>> testGetByTrack(String raceTrackId) throws Exception {
    String sql = "SELECT * FROM RaceLap WHERE raceTrackId = UNHEX(?);";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, (statement) -> {
      statement.setString(1, raceTrackId);
    }));
    ArrayList<RaceLap> result = new ArrayList<>();
    for (Object row : rows) {
      result.add(parseFromDB((JSONObject) row));
    }
    return completedFuture(result);
  }

  public static CompletableFuture<ArrayList<RaceLap>> getBestTimes(String raceTrackId) throws Exception {
    String sql = "SELECT * FROM RaceLap r\n" +
    "JOIN (\n" +
    "    SELECT raceTrackId, userId, MIN(millis) AS millis FROM RaceLap\n" +
    "    WHERE raceTrackId = UNHEX(?)\n" +
    "    GROUP BY userId\n" +
    ") m\n" +
    "ON r.raceTrackId = m.raceTrackId AND r.userId = m.userId AND r.millis = m.millis \n" +
    "ORDER BY r.millis ASC\n" +
    "LIMIT 10;";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, (statement) -> {
      statement.setString(1, raceTrackId);
    }));
    ArrayList<RaceLap> result = new ArrayList<>();
    for (Object row : rows) {
      result.add(parseFromDB((JSONObject) row));
    }
    return completedFuture(result);
  }

  public static CompletableFuture<RaceLap> getSingleBestTime(String raceTrackId, String userId) throws Exception {
    String sql = "SELECT * FROM RaceLap\n" +
    "WHERE raceTrackId = UNHEX(?)\n" +
    "AND userId = UNHEX(?)\n" +
    "ORDER BY millis ASC\n" +
    "LIMIT 1;";
    JSONArray rows = await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceTrackId);
        statement.setString(2, userId);
      })
    );
    if (rows.size() == 0) return completedFuture(null);
    return completedFuture(parseFromDB((JSONObject) rows.get(0)));
  }

  public static CompletableFuture<String> insertLap(
    String raceTrackId,
    String userId,
    int millis,
    JSONObject data
  ) throws Exception {
    String raceLapId = MySQLHelper.newId();
    String sql = "INSERT INTO RaceLap (\n" +
    "raceLapId,\n" +
    "raceTrackId,\n" +
    "userId,\n" +
    "millis,\n" +
    "version,\n" +
    "data\n" +
    ") VALUES (\n" +
    // raceLapId
    "UNHEX(?),\n" +
    // raceTrackId
    "UNHEX(?),\n" +
    // userId
    "UNHEX(?),\n" +
    // millis
    "?,\n" +
    // version
    "?,\n" +
    // data
    "?\n" +
    ");";
    await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceLapId);
        statement.setString(2, raceTrackId);
        statement.setString(3, userId);
        statement.setInt(4, millis);
        statement.setString(5, Main.version);
        statement.setString(6, data.toJSONString());
      })
    );
    return completedFuture(raceLapId);
  }
}
