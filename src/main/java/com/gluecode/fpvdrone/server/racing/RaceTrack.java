package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import com.jme3.math.Vector3f;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class RaceTrack {
  public String raceTrackId;
  public String ownerUserId;
  public String name;
  public String dimension;
  public int startPosX;
  public int startPosZ;
  public int startPosY;
  public boolean deleted;
  public Timestamp dateUpdated;
  public Timestamp dateCreated;

  // Derived:
  public ArrayList<RaceGate> gates;

  public RaceTrack(JSONObject data) {
    this.raceTrackId = (String) data.get("raceTrackId");
    this.ownerUserId = (String) data.get("ownerUserId");
    this.name = (String) data.get("name");
    this.dimension = (String) data.get("dimension");
    this.startPosX = (int) data.get("startPosX");
    this.startPosY = (int) data.get("startPosY");
    this.startPosZ = (int) data.get("startPosZ");
    this.deleted = (boolean) data.get("deleted");
    this.dateUpdated = (Timestamp) data.get("dateUpdated");
    this.dateCreated = (Timestamp) data.get("dateCreated");
  }

  public CompletableFuture<Void> loadGates(BiPredicate<String, BlockPos> checkSolid) throws Exception {
    this.gates = await(RaceGate.getGates(this.raceTrackId));
    RaceGate currentGate = null;
    try {
      for (RaceGate gate : this.gates) {
        currentGate = gate;
        await(gate.computeBoundaries(checkSolid));
      }
    } catch (CompletionException e) {
      RaceGateException ex = (RaceGateException) e.getCause();
      throw new Exception("The track has broken gates. " + ex.endUserReason + "\nCheck near [" + currentGate.a.getX() + ", " + currentGate.a.getY() + ", " + currentGate.a.getZ() + "].");
    }
    return completedFuture(null);
  }

  /*
    Tracks are soft deleted.
    `dimension` is not an index because it has low cardinality (only 3 possible values).
  * */
  public static void createTable() throws Exception {
    String sql = "CREATE TABLE IF NOT EXISTS RaceTrack (\n" +
    "`raceTrackId` BINARY(16) NOT NULL,\n" +
    "`ownerUserId` BINARY(16) NOT NULL,\n" +
    "`name` VARCHAR(32) NOT NULL,\n" +
    "`dimension` VARCHAR(32) NOT NULL,\n" +
    "`startPosX` INT NOT NULL,\n" +
    "`startPosZ` INT NOT NULL,\n" +
    "`startPosY` TINYINT UNSIGNED NOT NULL,\n" +
    "`deleted` BOOLEAN DEFAULT FALSE,\n" +
    "`dateUpdated` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
    "`dateCreated` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
    "PRIMARY KEY (`raceTrackId`),\n" +
    "INDEX `OWNERUSERIDINDEX` (`ownerUserId` ASC),\n" +
    "INDEX `STARTPOSXINDEX` (`startPosX` ASC),\n" +
    "INDEX `STARTPOSYINDEX` (`startPosY` ASC),\n" +
    "INDEX `STARTPOSZINDEX` (`startPosZ` ASC),\n" +
    "UNIQUE(name, deleted)\n" +
    ");";
    MySQLHelper.prepareAndExecute(sql, null).join();
  }

  public static CompletableFuture<Void> truncate() throws Exception {
    if (!MySQLHelper.DB_HOST.equals("localhost")) return completedFuture(null);
    String sql = "TRUNCATE TABLE RaceTrack;";
    await(MySQLHelper.prepareAndExecute(sql, null));
    return completedFuture(null);
  }

  public static CompletableFuture<ArrayList<RaceTrack>> testSelectAll() throws Exception {
    String sql = "SELECT * FROM RaceTrack;";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, null));
    ArrayList<RaceTrack> result = new ArrayList<>();
    for (Object row : rows) {
      result.add(new RaceTrack((JSONObject) row));
    }
    return completedFuture(result);
  }

  public static CompletableFuture<RaceTrack> testGetTrackById(String raceTrackId) throws Exception {
    String sql = "SELECT * FROM RaceTrack " + "WHERE raceTrackId = UNHEX(?);";
    JSONArray data = await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceTrackId);
      })
    );
    if (data == null || data.size() == 0) return completedFuture(null);
    return completedFuture(new RaceTrack((JSONObject) data.get(0)));
  }

  public static CompletableFuture<RaceTrack> getTrackById(String raceTrackId) throws Exception {
    String sql = "SELECT * FROM RaceTrack WHERE raceTrackId = UNHEX(?) AND !deleted;";
    JSONArray data = await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceTrackId);
      })
    );
    if (data == null || data.size() == 0) return completedFuture(null);
    return completedFuture(new RaceTrack((JSONObject) data.get(0)));
  }

  public static CompletableFuture<RaceTrack> getTrackByName(String name) throws Exception {
    String fname = MySQLHelper.sanitizeKeyboard(name);
    String sql = "SELECT * FROM RaceTrack WHERE name = ? AND !deleted;";
    JSONArray data = await(
    MySQLHelper.prepareAndExecute(sql, (statement) -> {
      statement.setString(1, fname);
    })
    );
    if (data == null || data.size() == 0) return completedFuture(null);
    return completedFuture(new RaceTrack((JSONObject) data.get(0)));
  }

  public static CompletableFuture<RaceTrack> getTrack(String dimension, BlockPos pos) throws Exception {
    String sql = "SELECT * FROM RaceTrack " +
    "WHERE startPosX = ? " +
    "AND startPosZ = ? " +
    "AND startPosY = ? " +
    "AND dimension = ? " +
    "AND !deleted;";
    JSONArray data = await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setInt(1, pos.getX());
        statement.setInt(2, pos.getZ());
        statement.setInt(3, pos.getY());
        statement.setString(4, dimension);
      })
    );
    if (data == null || data.size() == 0) return completedFuture(null);
    return completedFuture(new RaceTrack((JSONObject) data.get(0)));
  }

  // utf-8 characters in track name are not supported.
  public static CompletableFuture<String> insertTrack(String ownerUserId, String name, String dimension, BlockPos startingPos) throws Exception {
    RaceTrack existingTrack = await(getTrack(dimension, startingPos));
    if (existingTrack != null) {
      throw new Exception("A track already exists at this position. " + startingPos);
    }

    String fownerUserId = ownerUserId.replaceAll("-", "").toUpperCase();
    // Only common letters may be accepted for international compatibility.
    // No spaces
    String raceTrackId = MySQLHelper.newId();
    String fname = MySQLHelper.sanitizeKeyboard(name);
    String sql = "INSERT INTO RaceTrack (\n" +
    "raceTrackId,\n" +
    "ownerUserId,\n" +
    "name,\n" +
    "dimension,\n" +
    "startPosX,\n" +
    "startPosZ,\n" +
    "startPosY\n" +
    ") VALUES (\n" +
    // raceTrackId
    "UNHEX(?),\n" +
    // ownerUserId
    "UNHEX(?),\n" +
    // name
    "?,\n" +
    // dimension
    "?,\n" +
    // startPosX
    "?,\n" +
    // startPosZ
    "?,\n" +
    // startPosY
    "?\n" +
    ");";
    await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceTrackId);
        statement.setString(2, fownerUserId);
        statement.setString(3, fname);
        statement.setString(4, dimension);
        statement.setInt(5, startingPos.getX());
        statement.setInt(6, startingPos.getZ());
        statement.setInt(7, startingPos.getY());
      })
    );
    return completedFuture(raceTrackId);
  }

  public static CompletableFuture<Void> softDelete(String dimension, BlockPos pos) throws Exception {
    String sql = "UPDATE RaceTrack SET " +
    "deleted = TRUE, " +
    "dateUpdated = CURRENT_TIMESTAMP " +
    "WHERE startPosX = ? " +
    "AND startPosZ = ? " +
    "AND startPosY = ? " +
    "AND dimension = ? " +
    "AND !`deleted`;";
    await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setInt(1, pos.getX());
        statement.setInt(2, pos.getZ());
        statement.setInt(3, pos.getY());
        statement.setString(4, dimension);
      })
    );
    return completedFuture(null);
  }

  public int hashCode() {
    return this.raceTrackId.hashCode();
  }

  public boolean equals(Object o) {
    if (!(o instanceof RaceTrack)) {
      return false;
    }
    if (this == o) {
      return true;
    }
    return this.raceTrackId.equals(((RaceTrack) o).raceTrackId);
  }
}
