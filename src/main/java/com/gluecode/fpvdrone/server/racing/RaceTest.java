package com.gluecode.fpvdrone.server.racing;

import com.ea.async.Async;
import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import com.jme3.math.Vector3f;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class RaceTest {

  @BeforeAll
  static void initAll() {
    Async.init();
    Main.isUnitTest = true;
    RaceDatabase.load();
  }

  void checkTablesExist() {
    assertTrue(MySQLHelper.checkTableExists("RaceTrack").join());
    assertTrue(MySQLHelper.checkTableExists("RaceGate").join());
  }

  void clearRaceTrack() throws Exception {
    RaceTrack.truncate().join();
    ArrayList<RaceTrack> rows = RaceTrack.testSelectAll().join();
    assertEquals(rows.size(), 0);
  }

  void clearRaceGate() throws Exception {
    RaceGate.truncate().join();
    ArrayList<RaceGate> rows = RaceGate.testSelectAll().join();
    assertEquals(rows.size(), 0);
  }

  void clearRaceLap() throws Exception {
    RaceLap.truncate().join();
    ArrayList<RaceLap> rows = RaceLap.selectAll().join();
    assertEquals(rows.size(), 0);
  }

  void insertAndReadRaceTrack() throws Exception {
    String ownerUserId = MySQLHelper.newId();
    String name = "test_track";
    String dimension = "minecraft:overworld";
    BlockPos startingPos = new BlockPos(0, 0, 0);
    RaceTrack.insertTrack(ownerUserId, name, dimension, startingPos).join();
    RaceTrack track = RaceTrack.getTrack(dimension, startingPos).join();
    assertEquals(ownerUserId, track.ownerUserId);
    assertNotEquals(ownerUserId, track.raceTrackId);
    assertEquals(track.name, name);
    assertEquals(track.dateCreated, track.dateUpdated);
    int diff = (int)(System.currentTimeMillis() - track.dateCreated.getTime());
    assertTrue(diff < 1000); // time of creation was less than a second ago.

    // Not possible to insert 2 tracks at the same position:
    boolean fail = false;
    try {
      RaceTrack.insertTrack(
      ownerUserId,
      name,
      dimension,
      startingPos
      ).join();
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    // But if you softDelete the original, then it should be possible to insert new track at the same position:
    RaceTrack.softDelete(dimension, startingPos).join();
    RaceTrack.insertTrack(
    ownerUserId,
    name,
    dimension,
    startingPos
    ).join();

    // Soft deletion again should work:
    RaceTrack.softDelete(dimension, startingPos).join();

    // Backslashes not allowed:
    String trackId = RaceTrack.insertTrack(ownerUserId, "test_\\track", dimension, new BlockPos(1, 1, 1)).join();
    RaceTrack trackBackslash = RaceTrack.testGetTrackById(trackId).join();
    assertEquals(trackBackslash.name, name);
  }

  void insertAndReadGate() throws Exception {
    String raceTrackId = MySQLHelper.newId();
    String dimension = "minecraft:overworld";
    BlockPos a = new BlockPos(1, 0, 0);
    Direction face = Direction.UP;
    BlockPos b = new BlockPos(0, 1, 0);
    int[] rowMin = new int[1];
    int[] rowMax = new int[1];
    rowMin[0] = 1;
    rowMax[0] = 2;
    RaceGate gate = new RaceGate(dimension, a, face, b);
    gate.origin = new BlockPos(0, 0, 0);
    gate.farthest = new BlockPos(1, 1, 1);
    RaceGate.insertGate(raceTrackId, 0, gate).join();

    ArrayList<RaceGate> gates = RaceGate.getGates(raceTrackId).join();
    assertEquals(gates.size(), 1);
    RaceGate readGate = gates.get(0);
    assertEquals(readGate.raceTrackId, readGate.raceTrackId);
    assertEquals(readGate.dimension, dimension);
    assertEquals(readGate.a, a);
    assertEquals(readGate.face, face);
    assertEquals(readGate.b, b);
    assertEquals(readGate.origin, new BlockPos(0, 0, 0));
    assertEquals(readGate.farthest, new BlockPos(1, 1, 1));
    assertEquals(readGate.rowMin, null);
    assertEquals(readGate.rowMax, null);
    int diff = (int)(System.currentTimeMillis() - readGate.dateCreated.getTime());
    assertTrue(diff < 5000); // time of creation was recent.
  }

  @Test
  void testDiscord() {
    Main.sendDiscord("This is a test.", Main.discordTest).join();
  }

  @Test
  void testCheckSolidGenerator() {
    String dimension = "minecraft:overworld";
    String[] strings = {
    "\n" +
    "xxx",

    "\n" +
    "x x",

    "\n" +
    "xxx"
    };
    BiPredicate<String, BlockPos> checkSolid = generateCheckSolid(dimension, strings);
    assertEquals(checkSolid.test(dimension, new BlockPos(0, 0, 0)), false);
    assertEquals(checkSolid.test(dimension, new BlockPos(0, 1, 0)), false);
    assertEquals(checkSolid.test(dimension, new BlockPos(0, 2, 0)), false);

    assertEquals(checkSolid.test(dimension, new BlockPos(0, 0, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(1, 0, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(2, 0, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(0, 1, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(1, 1, 1)), false);
    assertEquals(checkSolid.test(dimension, new BlockPos(2, 1, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(0, 2, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(1, 2, 1)), true);
    assertEquals(checkSolid.test(dimension, new BlockPos(2, 2, 1)), true);
  }

  void createAndUseSimpleTrack() throws Exception {
    String userId = MySQLHelper.newId();
    BlockPos startingPos = new BlockPos(0, 0, 0);
    String dimension = "minecraft:overworld";
    String trackName = "simple_track";
    BlockKey startKey = new BlockKey(dimension, startingPos);
    boolean fail = false;

    RaceBuild.startTrack(userId, dimension, startingPos, null);
    RaceBuild.setTrackName(userId, trackName, null).join();
    assertTrue(RaceBuild.trackName.get(userId).equals(trackName));

    assertEquals(RaceBuild.checkBuildingMode(userId), true);
    assertEquals(RaceBuild.startingBlock.get(userId), startKey);

    String[] strings = {
    "xxxxx\n" +
    "xxxxx\n" +
    "xxxxx\n" +
    "xxxxx\n" +
    "xxxxx",

    "     \n" +
    "xxxxx\n" +
    " x   \n" +
    "x   x\n" +
    " x   ",

    "     \n" +
    "x   x\n" +
    " x   \n" +
    "x   x\n" +
    " x   ",

    "     \n" +
    "x   x\n" +
    " x   \n" +
    "x   x\n" +
    " x   ",

    "     \n" +
    "x   x\n" +
    " x   \n" +
    "x   x\n" +
    " x   ",

    "     \n" +
    "xxxxx\n" +
    " x   \n" +
    "xxxxx\n" +
    " x   "
    };
    BiPredicate<String, BlockPos> checkSolid = generateCheckSolid(dimension, strings);

    // mark the first gate:
    BlockPos a1 = new BlockPos(1, 1, 1);
    RaceBuild.addBlock(userId, dimension, a1, Direction.UP, checkSolid, null).join();
    assertEquals(RaceBuild.completedGates.get(userId).size(), 1);

    // Attempt to finish track with only 1 gate should fail.
    try {
      RaceBuild.addBlock(userId, dimension, startingPos, Direction.UP, checkSolid, null).join();
    } catch (Exception e) {
      assertEquals("The track must contain at least 2 gates.", e.getMessage());
    }

    // mark the second gate:
    BlockPos a2 = new BlockPos(1, 0, 3);
    fail = false;
    try {
      RaceBuild.addBlock(userId, dimension, a2, Direction.UP, checkSolid, null).join();
    } catch (Exception e) {
      fail = true;
      assertEquals("Two possible gates were found. Choose another block to specify which one you want to add.", e.getMessage());
    }
    assertTrue(fail);

    BlockPos b2 = new BlockPos(2, 0, 3);
    RaceBuild.addBlock(userId, dimension, b2, Direction.UP, checkSolid, null).join();
    assertEquals(RaceBuild.completedGates.get(userId).size(), 2);

    // complete the track:
    // different dimension should fail:
    fail = false;
    try {
      RaceBuild.addBlock(userId, "minecraft:end", startingPos, Direction.UP, checkSolid, null).join();
    } catch (Exception e) {
      fail = true;
      assertEquals("Gate block must be solid.", e.getMessage());
    }
    assertTrue(fail);
    RaceBuild.addBlock(userId, dimension, startingPos, Direction.UP, checkSolid, null).join();
    assertEquals(RaceBuild.checkBuildingMode(userId), false);

    // verify DB integrity:
    RaceTrack track = RaceTrack.getTrack(dimension, startingPos).join();
    track.loadGates(checkSolid).join();

    assertEquals(track.name, trackName);
    assertEquals(track.dimension, dimension);
    assertEquals(track.startPosX, 0);
    assertEquals(track.startPosY, 0);
    assertEquals(track.startPosZ, 0);

    assertEquals(track.gates.get(0).index, 0);
    assertEquals(track.gates.get(1).index, 1);

    assertEquals(track.gates.get(0).a, a1);
    assertEquals(track.gates.get(0).face, Direction.UP);
    assertEquals(track.gates.get(1).a, a2);
    assertEquals(track.gates.get(1).face, Direction.UP);
    assertEquals(track.gates.get(1).b, b2);

    assertEquals(track.gates.get(0).origin, new BlockPos(0,1,1));
    assertEquals(track.gates.get(0).farthest, new BlockPos(4,5,1));
    assertEquals(track.gates.get(1).origin, new BlockPos(0,0,3));
    assertEquals(track.gates.get(1).farthest, new BlockPos(4,5,3));

    assertArrayEquals(track.gates.get(0).rowMin, new int[]{1, 0, 0, 0, 1});
    assertArrayEquals(track.gates.get(1).rowMin, new int[]{1, 0, 0, 0, 0, 1});
    assertArrayEquals(track.gates.get(0).rowMax, new int[]{3, 4, 4, 4, 3});
    assertArrayEquals(track.gates.get(1).rowMax, new int[]{3, 4, 4, 4, 4, 3});

    assertEquals(track.gates.get(0).dimension, dimension);
    assertEquals(track.gates.get(1).dimension, dimension);

    Vector3f playerPos = new Vector3f(0, 1, 0);

    // Enter racing mode:
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos, null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), true);

    // Test load only once:
    String otherUserId = MySQLHelper.newId();
    int nCalls = MySQLHelper.nCalls;
    RaceNavigate.handleRightClickTrack(otherUserId, dimension, startingPos, checkSolid, playerPos, null).join();
    assertEquals(RaceNavigate.checkRacingMode(otherUserId), true);
    assertEquals(MySQLHelper.nCalls, nCalls);

    // Exit racing mode:
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos,null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), false);
    assertEquals(RaceNavigate.startToUsers.size(), 1);
    assertEquals(RaceNavigate.startToTrack.size(), 1);

    // Right click should not load track since it's already loaded:
    nCalls = MySQLHelper.nCalls;
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos,null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), true);
    assertEquals(MySQLHelper.nCalls, nCalls);
    assertEquals(RaceNavigate.startToUsers.size(), 1);
    assertEquals(RaceNavigate.startToTrack.size(), 1);
    assertEquals(RaceNavigate.startToUsers.get(startKey).size(), 2);

    // Unload the track when all users are exited:
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos,null).join();
    RaceNavigate.handleRightClickTrack(otherUserId, dimension, startingPos, checkSolid, playerPos,null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), false);
    assertEquals(RaceNavigate.checkRacingMode(otherUserId), false);
    assertEquals(RaceNavigate.startToUsers.size(), 0);
    assertEquals(RaceNavigate.startToTrack.size(), 0);

    // Enter racing mode at this time should cause a full-load of the track:
    nCalls = MySQLHelper.nCalls;
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos, null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), true);
    assertEquals(MySQLHelper.nCalls, nCalls + 2);

    // Breaking or adding a gate block when in racing mode should kick everyone out of the race.
    RaceNavigate.changedGateBlock(dimension, new BlockPos(2, 1, 1), null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), false);
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos, null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), true);
    RaceNavigate.changedGateBlock(dimension, new BlockPos(2, 3, 1), null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), false);
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos, null).join();
    assertEquals(RaceNavigate.checkRacingMode(userId), true);

    // Move through the track
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 0), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 1.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 1);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 2.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 1);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 3.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);

    // Move back to beginning
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 2.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 0), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 1);
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 4.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);

    // Move back to beginning
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(4, 10, 4), null).join();
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(4, 10, 0), null).join();
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 0), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);

    // Move through both gates in 1 time step
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 4.5f), null).join();
    assertEquals(RaceNavigate.userNextGate.get(userId), 0);

    // And back to the beginning once more to complete the lap:
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(4, 10, 4), null).join();
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(4, 10, 0), null).join();
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 0), null).join();
    RaceNavigate.onPlayerMoved(userId, dimension, new Vector3f(1, 2, 1.5f), null).join();

    // Check recorded laps:
    ArrayList<RaceLap> laps = RaceLap.testGetByTrack(track.raceTrackId).join();
    assertEquals(laps.size(), 3);

    // Exit racing:
    RaceNavigate.handleRightClickTrack(userId, dimension, startingPos, checkSolid, playerPos, null).join();

    RaceBuild.removeTrack(userId, dimension, startingPos, null).join();
  }


  void createTrackOutsideFace() throws Exception {
    String userId = MySQLHelper.newId();
    BlockPos startingPos = new BlockPos(0, 0, 0);
    String dimension = "minecraft:overworld";
    String trackName = "simple_track";
    BlockKey startKey = new BlockKey(dimension, startingPos);
    boolean fail = false;

    String[] strings = {
    "xxxxx",
    "x   x",
    "x   x",
    "x   x",
    "xxxxx"
    };
    BiPredicate<String, BlockPos> checkSolid = generateCheckSolid(dimension, strings);

    RaceBuild.startTrack(userId, dimension, startingPos, null);
    RaceBuild.setTrackName(userId, trackName, null).join();
    BlockPos a1 = new BlockPos(4, 2, 0);
    RaceBuild.addBlock(userId, dimension, a1, Direction.EAST, checkSolid, null).join();
    assertEquals(RaceBuild.completedGates.get(userId).size(), 1);
  }

  @Test
  void runTest() throws Exception {
    checkTablesExist();
    clearRaceTrack();
    clearRaceGate();
    clearRaceLap();
    insertAndReadRaceTrack();
    insertAndReadGate();

    clearRaceTrack();
    clearRaceGate();
    createAndUseSimpleTrack();
    createAndUseSimpleTrack(); // second run should work because the first one should have soft deleted everything.

//    createTrackOutsideFace();

    // todo: remove track.
  }

  @Test
  void testGateShapes() throws Exception {
    boolean fail = false;
    buildGate(
    "xxxx\n" +
    "x  x\n" +
    "x  x\n" +
    "xxxx"
    );
    buildGate(
    " xx \n" +
    "x  x\n" +
    "x  x\n" +
    " xx "
    );
    buildGate(
    " xx \n" +
    "x  x\n" +
    " x x\n" +
    "  x "
    );
    buildGate(
    " xxxxxxxxxxxxxx \n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    "x              x\n" +
    " xxxxxxxxxxxxxx"
    );

    buildGate3(
    "    xxxx    \n" +
    "  xx    xx  \n" +
    " x        x \n" +
    " x        x \n" +
    "x          x\n" +
    "x          x\n" +
    "x          x\n" +
    "x          x\n" +
    " x        x \n" +
    " x        x \n" +
    "  xx    xx  \n" +
    "    xxxx    "
    );

    fail = false;
    try {
      buildGate(
      " xx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xxxxxxxxxxxxxxxxx \n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      "x                 x\n" +
      " xxxxxxxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    buildGate(
    " xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx \n" +
    "x                               x\n" +
    " xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    );

    fail = false;
    try {
      buildGate(
      " xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx \n" +
      "x                                x\n" +
      " xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xx xxxxxxxxxxx \n" +
      "x  x           x\n" +
      "x              x\n" +
      " xxxxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xx xxxxxxxxxxx \n" +
      "x              x\n" +
      "x              x\n" +
      " xxxxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xxxxxxxxxxxxxx \n" +
      "x              x\n" +
      "x      x       x\n" +
      "x              x\n" +
      " xxxxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xx             \n" +
      "x  xxxxxxxxxxxxx\n" +
      "x              x\n" +
      "x x            x\n" +
      " x xxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    buildGate(
    " xx             \n" +
    "x  xxxxxxxxxxxx \n" +
    "x              x\n" +
    "x              x\n" +
    " xxxxxxxxxxxxxx"
    );

    fail = false;
    try {
      buildGate(
      " xxxxxxxxxxxxxx \n" +
      "x              x\n" +
      "x              x\n" +
      "x x            x\n" +
      " x xxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xxxxxxxxxxxxxx \n" +
      "xxx            x\n" +
      "x              x\n" +
      "x x            x\n" +
      " x xxxxxxxxxxxx"
      );
    } catch (Exception e) {
      fail = true;
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate(
      " xx\n" +
      "x x\n" +
      "x x\n" +
      "x x\n" +
      " x"
      );
    } catch (Exception e) {
      fail = true;
      assertEquals("B was not visited.", e.getMessage());
    }
    assertTrue(fail);

    fail = false;
    try {
      buildGate2(
      " xxxxxx\n" +
      "x x    x\n" +
      "x x    x\n" +
      "x x    x\n" +
      " xxxxxx"
      );
    } catch (Exception e) {
      fail = true;
      assertEquals("Starting blocks do not have the correct solidity.", e.getMessage());
    }
    assertTrue(fail);

    fail = false;
    try {
      String[] template = {
      "xxxxx",
      "x   x",
      "x   x",
      "x   x",
      "xxxxx"
      };
      buildGate4(template);
    } catch (Exception e) {
      fail = true;
      assertEquals("Air was not visited.", e.getMessage());
    }
    assertTrue(fail);

    String[] template = {
    "xxxxx",
    "x   x",
    "x   x",
    "x   x",
    "xxxxx"
    };
    buildGate5(template, new BlockPos(1, 0, 0), Direction.UP, new BlockPos(2, 0, 0));

    String[] template2 = {
    "x\n" +
    "x\n" +
    "x\n" +
    "x\n" +
    "x\n",

    "x\n" +
    "\n" +
    "\n" +
    "\n" +
    "x\n",

    "x\n" +
    "\n" +
    "\n" +
    "\n" +
    "x\n",

    "x\n" +
    "\n" +
    "\n" +
    "\n" +
    "x\n",

    "x\n" +
    "x\n" +
    "x\n" +
    "x\n" +
    "x\n"
    };
    buildGate5(template2, new BlockPos(0, 0, 1), Direction.UP, new BlockPos(0, 0, 2));

    String[] template3 = {
    "    xx    ",
    "  xx  xx  ",
    " x      x  ",
    " x      x  ",
    "x        x ",
    "x        x ",
    " x      x  ",
    " x      x  ",
    "  xx  xx  ",
    "    xx    "
    };
    buildGate5(template3, new BlockPos(3, 8, 0), Direction.EAST, new BlockPos(4, 9, 0));
  }

  private static void buildGate(String template) throws RaceGateException {
    String[] t = new String[1];
    t[0] = template;
    String dimension = "minecraft:overworld";
    RaceMath.loadGate(
      dimension,
      new BlockPos(1, 0, 0),
      Direction.SOUTH,
      new BlockPos(2, 0, 0),
      generateCheckSolid(dimension, t),
      null
    );
  }

  private static void buildGate2(String template) throws RaceGateException {
    String[] t = new String[1];
    t[0] = template;
    String dimension = "minecraft:overworld";
    RaceMath.loadGate(
    dimension,
    new BlockPos(2, 0, 0),
    Direction.SOUTH,
    new BlockPos(2, 0, 1),
    generateCheckSolid(dimension, t),
    null
    );
  }

  private static void buildGate3(String template) throws RaceGateException {
    String[] t = new String[1];
    t[0] = template;
    String dimension = "minecraft:overworld";
    RaceMath.loadGate(
    dimension,
    new BlockPos(5, 0, 0),
    Direction.SOUTH,
    new BlockPos(4, 0, 0),
    generateCheckSolid(dimension, t),
    null
    );
  }

  private static void buildGate4(String[] template) throws RaceGateException {
    String dimension = "minecraft:overworld";
    RaceMath.loadGate(
    dimension,
    new BlockPos(4, 2, 0),
    Direction.EAST,
    new BlockPos(4, 3, 0),
    generateCheckSolid(dimension, template),
    null
    );
  }

  private static void buildGate5(String[] template, BlockPos a, Direction face, BlockPos b) throws RaceGateException {
    String dimension = "minecraft:overworld";
    RaceMath.loadGate(
    dimension,
    a,
    face,
    b,
    generateCheckSolid(dimension, template),
    null
    );
  }

  /*
  The input is an array of strings.
  Each string is an ASCII art representation of a 2D slice of the world.

  Example:
     ooo\n
     o o\n
     ooo\n

   z-coord is vertical axis.
   x-coord is horizontal.

   Any character will be interpreted as a solid block. Spaces are non-solid.

   The string at index 0 is y=0.
   The string at index 1 is y=1.
   etc.

   The return value is the checkSolid function.
  * */
  private static BiPredicate<String, BlockPos> generateCheckSolid(String dimension, String[] template) {
    HashMap<BlockPos, Boolean> solid = new HashMap<>();

    for (int y = 0; y < template.length; y++) {
      String layer = template[y];
      String[] rows = layer.split("\\r?\\n");
      for (int z = 0; z < rows.length; z++) {
        String row = rows[z];
        for (int x = 0; x < row.length(); x++) {
          char c = row.charAt(x);
          if (c != ' ') {
            solid.put(new BlockPos(x, y, z), true);
          }
        }
      }
    }

    return (String testDim, BlockPos testPos) -> {
      Boolean isSolid = solid.get(testPos);
      if (isSolid == null) {
        return false;
      }
      return isSolid && testDim.equals(dimension);
    };
  }
}
