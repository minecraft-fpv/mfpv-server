package com.gluecode.fpvdrone.server.racing;

import com.github.f4b6a3.uuid.UuidCreator;
import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import com.jme3.math.Vector3f;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.IOException;
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

public class RaceGate {
  // These values exist in DB but they come from Minecraft:
  // They will also be packed in the DB as the data field.
  public String dimension;
  public BlockPos a;
  public Direction face;
  public BlockPos b;

  // Database:
  public String raceTrackId;
  public int index;
  public boolean deleted; // Not really used right now since gates can only be obtained by raceTrackId.
  public Timestamp dateCreated;

  // They are also in the DB and indexed for finding a gate.
  // But they are also set by computeBoundaries():
  public BlockPos origin; // The bottom-left corner of BB (more negative)
  public BlockPos farthest; // The top-right corner of BB (more positive)
  
  // The following does not exist in DB and they will remain null unless computeBoundaries() is called:
  public int[] rowMin; // left most block (solid) for a given row (relative to some up direction).
  public int[] rowMax; // right most block (solid) for a given row (relative to some up direction).
  public Vector3f right;
  public Vector3f up;
  public Vector3f normal;
  public ArrayList<BlockPos> path;

  public RaceGate(String dimension, BlockPos a, Direction face, BlockPos b) {
    this.dimension = dimension;
    this.a = a;
    this.face = face;
    this.b = b;
  }

  /*
  This is async to avoid blocking the thread when loading large arrays.
  * */
  public CompletableFuture<Void> computeBoundaries(BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
    try {
      await(CompletableFuture.runAsync(() -> {
        try {
          if (this.dimension == null || this.a == null || this.face == null || this.b == null) {
            throw new RaceGateException("Gate has null values from DB.", "This track is not supported in this version. Please rebuild the track.");
          } else {
            RaceMath.loadGate(this.dimension, this.a, this.face, this.b, checkSolid, this);
          }
        } catch (RaceGateException e) {
          throw new CompletionException(e);
        }
      }));
    } catch (CompletionException e) {
      throw (RaceGateException) e.getCause();
    }
    return completedFuture(null);
  }

  public void setBoundaries(BlockPos origin, BlockPos farthest, int[] rowMin, int[] rowMax, Vector3f right, Vector3f up, Vector3f normal, ArrayList<BlockPos> path) {
    this.origin = origin;
    this.farthest = farthest;
    this.rowMin = rowMin;
    this.rowMax = rowMax;
    this.right = right;
    this.up = up;
    this.normal = normal;
    this.path = path;
  }

  public static JSONArray packIntArray(int[] array) {
    JSONArray result = new JSONArray();
    for (int i : array) {
      result.add(i);
    }
    return result;
  }

  public static JSONArray packBlockPos(BlockPos blockPos) {
    JSONArray result = new JSONArray();
    result.add(blockPos.getX());
    result.add(blockPos.getY());
    result.add(blockPos.getZ());
    return result;
  }

  public static JSONObject packData(RaceGate gate) {
    JSONObject data = new JSONObject();
    data.put("dimension", gate.dimension);
    data.put("a", packBlockPos(gate.a));
    data.put("face", packDirection(gate.face));
    data.put("b", packBlockPos(gate.b));
    return data;
  }

  public static int[] unpackIntArray(JSONArray data) {
    int[] result = new int[data.size()];
    for (int i = 0; i < data.size(); i++) {
      result[i] = Math.toIntExact((long) data.get(i));
    }
    return result;
  }

  public static BlockPos unpackBlockPos(JSONArray data) {
    int x = Math.toIntExact((long) data.get(0));
    int y = Math.toIntExact((long) data.get(1));
    int z = Math.toIntExact((long) data.get(2));
    return new BlockPos(x, y, z);
  }

  public static int packDirection(Direction direction) {
    return direction.get3DDataValue();
  }

  public static Direction unpackDirection(long data) {
    return Direction.from3DDataValue(Math.toIntExact(data));
  }

  public static RaceGate unpackRow(JSONObject row) {
    JSONObject data = (JSONObject) JSONValue.parse((String) row.get("data"));

    String dimension = (String) data.get("dimension");
    BlockPos a = unpackBlockPos((JSONArray) data.get("a"));
    Direction face = unpackDirection((long) data.get("face"));
    BlockPos b = unpackBlockPos((JSONArray) data.get("b"));
    RaceGate gate = new RaceGate(dimension, a, face, b);
    
    gate.origin = new BlockPos((int) row.get("originX"), (int) row.get("originY"), (int) row.get("originZ"));
    gate.farthest = new BlockPos((int) row.get("farthestX"), (int) row.get("farthestY"), (int) row.get("farthestZ"));
    gate.raceTrackId = (String) row.get("raceTrackId");
    gate.index = (int) row.get("index");
    gate.deleted = (boolean) row.get("deleted");
    gate.dateCreated = (Timestamp) row.get("dateCreated");

    return gate;
  }

  public static ArrayList<RaceGate> unpackRows(JSONArray rows) {
    ArrayList<RaceGate> result = new ArrayList<>();
    for (Object row : rows) {
      result.add(unpackRow((JSONObject) row));
    }
    return result;
  }

  public static void createTable() throws Exception {
    String sql = "CREATE TABLE IF NOT EXISTS RaceGate (\n" +
    "`raceGateId` BINARY(16) NOT NULL,\n" +
    "`raceTrackId` BINARY(16) NOT NULL,\n" +
    "`index` SMALLINT UNSIGNED NOT NULL,\n" +
    "`originX` INT NOT NULL,\n" +
    "`originY` TINYINT UNSIGNED NOT NULL,\n" +
    "`originZ` INT NOT NULL,\n" +
    "`farthestX` INT NOT NULL,\n" +
    "`farthestY` TINYINT UNSIGNED NOT NULL,\n" +
    "`farthestZ` INT NOT NULL,\n" +
    "`data` JSON NOT NULL,\n" +
    "`deleted` BOOLEAN DEFAULT FALSE,\n" +
    "`dateUpdated` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
    "`dateCreated` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
    "PRIMARY KEY (`raceGateId`),\n" +
    "INDEX `RACETRACKIDINDEX` (`raceTrackId` ASC),\n" +
    "INDEX `ORIGINXINDEX` (`originX` ASC),\n" +
    "INDEX `ORIGINYINDEX` (`originY` ASC),\n" +
    "INDEX `ORIGINZINDEX` (`originZ` ASC),\n" +
    "INDEX `FARTHESTXINDEX` (`farthestX` ASC),\n" +
    "INDEX `FARTHESTYINDEX` (`farthestY` ASC),\n" +
    "INDEX `FARTHESTZINDEX` (`farthestZ` ASC),\n" +
    "UNIQUE(`raceTrackId`, `index`)\n" +
    ");";
    MySQLHelper.prepareAndExecute(sql, null).join();
  }

  public static CompletableFuture<Void> truncate() throws Exception {
    if (!MySQLHelper.DB_HOST.equals("localhost")) return completedFuture(null);
    String sql = "TRUNCATE TABLE RaceGate;";
    await(MySQLHelper.prepareAndExecute(sql, null));
    return completedFuture(null);
  }

  public static CompletableFuture<ArrayList<RaceGate>> testSelectAll() throws Exception {
    String sql = "SELECT * FROM RaceGate;";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, null));
    return completedFuture(unpackRows(rows));
  }

  public static CompletableFuture<ArrayList<RaceGate>> getGates(String raceTrackId) throws Exception {
    String sql = "SELECT * FROM RaceGate\n" +
    "WHERE raceTrackId = UNHEX(?)\n" +
    "ORDER BY `index`;";
    JSONArray rows = await(MySQLHelper.prepareAndExecute(sql, (statement) -> {
      statement.setString(1, raceTrackId);
    }));
    return completedFuture(unpackRows(rows));
  }

  /*
  Gets the gate if the block is inside the gates's AABB.
  Returns an array because AABB may overlap.
  * */
  public static CompletableFuture<ArrayList<RaceGate>> getGateByBlock(String dimension, BlockPos pos) throws Exception {
    String sql = "SELECT * FROM RaceGate\n" +
    "WHERE data->>'$.dimension' = ?\n" +
    "AND IF(\n" +
    "    originX = farthestX, -- fixedX\n" +
    "    originX = ?,\n" +
    "    originX <= ? && ? <= farthestX\n" +
    ")\n" +
    "AND IF(\n" +
    "    originY = farthestY, -- fixedY\n" +
    "    originY = ?,\n" +
    "    originY <= ? && ? <= farthestY\n" +
    ")\n" +
    "AND IF(\n" +
    "    originZ = farthestZ, -- fixedZ\n" +
    "    originZ = ?,\n" +
    "    originZ <= ? && ? <= farthestZ\n" +
    ")\n" +
    "AND !deleted;";
    JSONArray rows = await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, dimension);
        statement.setInt(2, pos.getX());
        statement.setInt(3, pos.getX());
        statement.setInt(4, pos.getX());
        statement.setInt(5, pos.getY());
        statement.setInt(6, pos.getY());
        statement.setInt(7, pos.getY());
        statement.setInt(8, pos.getZ());
        statement.setInt(9, pos.getZ());
        statement.setInt(10, pos.getZ());
      })
    );
    return completedFuture(unpackRows(rows));
  }

  public static @NotNull
  CompletableFuture<String> insertGate(String raceTrackId, int index, RaceGate gate) throws Exception {
    String raceGateId = MySQLHelper.newId();
    JSONObject data = packData(gate);
    String sql = "INSERT INTO RaceGate (\n" +
    "`raceGateId`,\n" +
    "`raceTrackId`,\n" +
    "`index`,\n" +
    "`originX`,\n" +
    "`originY`,\n" +
    "`originZ`,\n" +
    "`farthestX`,\n" +
    "`farthestY`,\n" +
    "`farthestZ`,\n" +
    "`data`\n" +
    ") VALUES (\n" +
    // raceGateId
    "UNHEX(?),\n" +
    // raceTrackId
    "UNHEX(?),\n" +
    // index
    "?,\n" +
    // originX
    "?,\n" +
    // originY
    "?,\n" +
    // originZ
    "?,\n" +
    // farthestX
    "?,\n" +
    // farthestY
    "?,\n" +
    // farthestZ
    "?,\n" +
    // data
    "?\n" +
    ");";
    await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceGateId);
        statement.setString(2, raceTrackId);
        statement.setInt(3, index);
        statement.setInt(4, gate.origin.getX());
        statement.setInt(5, gate.origin.getY());
        statement.setInt(6, gate.origin.getZ());
        statement.setInt(7, gate.farthest.getX());
        statement.setInt(8, gate.farthest.getY());
        statement.setInt(9, gate.farthest.getZ());
        statement.setString(10, data.toJSONString());
      })
    );
    return completedFuture(raceGateId);
  }

  public static CompletableFuture<Void> softDelete(String raceTrackId) throws Exception {
    String sql = "UPDATE RaceGate SET\n" +
    "deleted = TRUE,\n" +
    "dateUpdated = CURRENT_TIMESTAMP\n" +
    "WHERE raceTrackId = UNHEX(?);";
    await(
      MySQLHelper.prepareAndExecute(sql, (statement) -> {
        statement.setString(1, raceTrackId);
      })
    );
    return completedFuture(null);
  }
}
