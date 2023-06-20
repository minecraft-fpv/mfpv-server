package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.network.*;
import com.gluecode.fpvdrone.race.SerialRaceGate;
import com.gluecode.fpvdrone.race.SerialRaceTrack;
import com.gluecode.fpvdrone.server.Main;
import com.google.common.collect.Maps;
import com.jme3.math.FastMath;
import com.jme3.math.Plane;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class RaceNavigate {
  private static Map<String, Boolean> isRacingMode = Maps.newConcurrentMap();
  public static Map<String, RaceTrack> userToTrack = Maps.newConcurrentMap();
  public static Map<BlockKey, ArrayList<String>> startToUsers = Maps.newConcurrentMap();
  public static Map<BlockKey, RaceTrack> startToTrack = Maps.newConcurrentMap();
  public static Map<String, Vector3f> prevUserPos = Maps.newConcurrentMap();
  public static Map<String, Integer> userNextGate = Maps.newConcurrentMap();
  public static Map<String, Long> userStartTime = Maps.newConcurrentMap();

  public static void load() {
    RaceDatabase.load();
  }

  /*
  Check if a user is in racing mode.
  * */
  public static boolean checkRacingMode(String userId) {
    Boolean racingMode = isRacingMode.get(userId);
    RaceTrack raceTrack = userToTrack.get(userId);
    return racingMode != null && racingMode && raceTrack != null;
  }

  /*
  Check if the BlockPos is a registered track starting point.
  * */
  public static @NotNull
  CompletableFuture<Boolean> checkTrackStart(String dimension, BlockPos pos) throws Exception {
    RaceTrack track = await(RaceTrack.getTrack(dimension, pos));
    return completedFuture(track != null);
  }

  public static CompletableFuture<Void> handleRightClickTrack(String userId, String dimension, BlockPos startingPos, BiPredicate<String, BlockPos> checkSolid, Vector3f playerPos, @Nullable Entity entity) throws Exception {
    // If the player is already in racing mode, they will be switched to a different track
    // if they clicked a different track's startingPos.

    try {
      if (checkRacingMode(userId)) {
        RaceTrack current = userToTrack.get(userId);
        if (current.dimension.equals(dimension) &&
        current.startPosX == startingPos.getX() &&
        current.startPosY == startingPos.getY() &&
        current.startPosZ == startingPos.getZ()) {
          // User right clicked the starting block of the track they are already in.
          await(exitRacingMode(userId, entity, null));
          return completedFuture(null);
        } else {
          // If the user is already in racing mode, and this is a different track,
          // Then first exit out of racing mode in order to unload the previous track.
          await(exitRacingMode(userId, entity, null));
        }
      }
      await(enterRacingMode(userId, dimension, startingPos, checkSolid, playerPos, entity));
    } catch (Exception e) {
      Main.sendErrorMessage(entity, e.getMessage());
      throw e;
    }

    return completedFuture(null);
  }

  private static CompletableFuture<Void> enterRacingMode(String userId, String dimension, BlockPos startingPos, BiPredicate<String, BlockPos> checkSolid, Vector3f playerPos, @Nullable Entity entity) throws Exception {
    BlockKey startKey = new BlockKey(dimension, startingPos);

    // Get or load the track:
    RaceTrack track;
    if (startToTrack.get(startKey) == null) {
      // There is currently no track loaded for the given startKey.
      track = await(RaceTrack.getTrack(dimension, startingPos));
      await(track.loadGates(checkSolid));
      startToTrack.put(startKey, track);
    } else {
      track = startToTrack.get(startKey);
    }

    // Assign the user to the track:
    isRacingMode.put(userId, true);
    userToTrack.put(userId, track);
    userNextGate.put(userId, 0);
    prevUserPos.put(userId, playerPos);

    // Keep count of how many users are assigned to a track:
    if (startToUsers.get(startKey) == null) {
      ArrayList<String> users = new ArrayList<>();
      users.add(userId);
      startToUsers.put(startKey, users);
    } else {
      // handleRightClickTrack prevents a user from being added twice.
      startToUsers.get(startKey).add(userId);
    }

    Main.sendInfoMessage(entity, "You are racing track '" + track.name + "'.");
    if (!Main.isUnitTest) {
      try {
        PacketHandler.sendToAll(new SetRaceModePacket(
        true,
        RaceBuild.getUUID(track.raceTrackId),
        RaceBuild.getUUID(userId)
        ));
        // Send the best times of the everyone (including self) to the new racer
        ArrayList<RaceLap> bestLaps = await(RaceLap.getBestTimes(track.raceTrackId));
        for (RaceLap lap : bestLaps) {
          PacketHandler.sendTo(new LapBestPacket(lap.millis, RaceBuild.getUUID(track.raceTrackId), RaceBuild.getUUID(lap.userId)), (ServerPlayerEntity) entity);
        }

        // Send track and gate data:
        ArrayList<SerialRaceGate> serialGates = new ArrayList<>();
        for (RaceGate gate : track.gates) {
          SerialRaceGate serialGate = new SerialRaceGate(
            gate.dimension,
            gate.origin.getX(),
            gate.origin.getY(),
            gate.origin.getZ(),
            gate.farthest.getX(),
            gate.farthest.getY(),
            gate.farthest.getZ(),
            gate.rowMin,
            gate.rowMax,
            (int) gate.right.getX(),
            (int) gate.right.getY(),
            (int) gate.right.getZ(),
            (int) gate.up.getX(),
            (int) gate.up.getY(),
            (int) gate.up.getZ()
          );
          serialGates.add(serialGate);
        }
        PacketHandler.sendTo(new SerialRaceTrack(RaceBuild.getUUID(track.raceTrackId), track.name, serialGates), (ServerPlayerEntity) entity);
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }

    return completedFuture(null);
  }

  public static CompletableFuture<Void> exitRacingMode(String userId, @Nullable Entity entity, @Nullable String reason) {
    RaceTrack track = userToTrack.get(userId);
    if (track == null) {
      // The user isn't assigned to a track.
      return completedFuture(null);
    }

    BlockPos startingPos = new BlockPos(track.startPosX, track.startPosY, track.startPosZ);
    BlockKey startKey = new BlockKey(track.dimension, startingPos);

    // Remove user from track:
    isRacingMode.remove(userId);
    userToTrack.remove(userId);
    userNextGate.remove(userId);
    userStartTime.remove(userId);

    // decrement count:
    startToUsers.get(startKey).remove(userId);

    // Unload track if no one is using it:
    if (startToUsers.get(startKey).size() == 0) {
      startToUsers.remove(startKey);
      startToTrack.remove(startKey);
    }

    // Clear user position:
    prevUserPos.remove(userId);

    if (reason == null) {
      Main.sendInfoMessage(entity, "You left racing mode.");
    } else {
      Main.sendErrorMessage(entity, "You were kicked out of racing mode because: " + reason);
    }

    if (!Main.isUnitTest) {
      try {
        PacketHandler.sendToAll(new SetRaceModePacket(
        false,
        RaceBuild.getUUID(track.raceTrackId),
        RaceBuild.getUUID(userId)
        ));
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }

    return completedFuture(null);
  }

  public static void exitAllPlayersFromTrack(BlockKey key, @Nullable String reason) {
    ArrayList<String> userIds = startToUsers.get(key);
    if (userIds == null) {
      // No one is racing the track.
      return;
    }
    userIds = new ArrayList<>(userIds);
    for (String userId : userIds) {
      ServerPlayerEntity entity = RaceBuild.getEntityFromId(userId);
      exitRacingMode(userId, entity, reason);
    }
  }

  public static CompletableFuture<Void> changedGateBlock(String dimension, BlockPos pos, @Nullable String reason) {
    // The entire method is in try catch in order to make Forge code not worry about errors.
    try {
      // All gates which have an AABB which contains the pos will be handled.
      // All players will be kicked out of the associated tracks.
      ArrayList<RaceGate> gates = await(RaceGate.getGateByBlock(
      dimension,
      pos
      ));
      // todo: this loop should be done without SQL queries:
      for (RaceGate gate : gates) {
        RaceTrack track = await(RaceTrack.getTrackById(gate.raceTrackId));
        BlockKey key = new BlockKey(
        track.dimension,
        new BlockPos(track.startPosX, track.startPosY, track.startPosZ)
        );
        exitAllPlayersFromTrack(key, reason);
      }
    } catch (Exception e) {
      Main.LOGGER.error(e.getMessage());
    }
    return completedFuture(null);
  }

  public static CompletableFuture<Void> onPlayerMoved(String userId, String dimension, Vector3f playerPos, @Nullable Entity entity) throws Exception {
    // prevPlayerPos should have been initialized in enterRacingMode

    // Do not process movement unless the player moved at least 1 block


    Vector3f intersectionHit = new Vector3f();
    int loopCount = 0;
    boolean passedGate;
    do {
      passedGate = checkUserPassedGate(userId, dimension, playerPos, intersectionHit);
      if (passedGate) {
        // Advance the target gate and handle lap completion:
        await(handleGatePassed(userId, entity));

        // Advancing the gate will allow the next call to checkUserPassedGate
        // to check if the user also passed through the next, next gate,
        // and so on
        // until the user fails to pass through a gate.
        prevUserPos.put(userId, intersectionHit);
      }
      loopCount++;
    } while (passedGate && loopCount <= RaceBuild.MAX_GATES_PER_TRACK);

    // todo: also track user movement for cheat detection.

    prevUserPos.put(userId, playerPos);
    return completedFuture(null);
  }

  /*
  The player's coordinates are actually the coordinates of the center at the bottom of player's collision box.
  The position of a block is actually the coordinates of the point at the lower northwest corner of the block, that is, the integer coordinates obtained by rounding down the coordinates inside the block.
  * */
  private static boolean checkUserPassedGate(String userId, String dimension, Vector3f playerPos, Vector3f intersectionOut) {
    Vector3f prevPos = prevUserPos.get(userId);
    if (playerPos.equals(prevPos)) {
      // No movement.
      return false;
    }
    RaceGate nextGate = getNextGate(userId);
    if (nextGate == null) {
      return false;
    }

    if (!dimension.equals(nextGate.dimension)) {
      return false;
    }

    // 1. line-plane intersection to figure out if the player intersected with the gate and where the intersection was.
    // 2. Check if the intersection block is inside the gate by:
    //    * check if crossingBlock is between gate.origin and gate.farthest;
    //    * figure out which "row" the crossingBlock is on.
    //    * figure out if the "column" the crossingBlock is on is between rowMin and rowMax.

    Vector3f positiveNormal = nextGate.normal.clone();
    if (FastMath.sign(positiveNormal.x) < 0) {
      positiveNormal.x *= -1;
    }
    if (FastMath.sign(positiveNormal.y) < 0) {
      positiveNormal.y *= -1;
    }
    if (FastMath.sign(positiveNormal.z) < 0) {
      positiveNormal.z *= -1;
    }

    // Check if player reached or crossed gate:
    Vector3f travelDir = playerPos.subtract(prevPos).normalizeLocal();
    Ray ray = new Ray(prevPos, travelDir);
    Plane plane = new Plane(nextGate.normal, new Vector3f(nextGate.origin.getX() + positiveNormal.x * 0.5f, nextGate.origin.getY() + positiveNormal.y * 0.5f, nextGate.origin.getZ() + positiveNormal.z * 0.5f));
    Vector3f rayHitOut = new Vector3f();
    ray.intersectsWherePlane(plane, rayHitOut);
    float travelDistance = playerPos.subtract(prevPos).lengthSquared();
    float minDistanceNeeded = rayHitOut.subtract(prevPos).lengthSquared();
    // negative bias needed for almost reaching gate and floating point errors.
    boolean gateHit = (travelDistance - minDistanceNeeded) > -(0.001f * 0.001f);
    if (!gateHit) {
      return false;
    }

    // Determine the block they were in when crossing:
    BlockPos crossingBlock = new BlockPos((int) FastMath.floor(rayHitOut.x), (int) FastMath.floor(rayHitOut.y), (int) FastMath.floor(rayHitOut.z));
    boolean pass = isGateBlock(dimension, crossingBlock, nextGate);
    intersectionOut.set(rayHitOut);
    return pass;
  }

  public static boolean isGateBlock(String dimension, BlockPos pos, RaceGate gate) {
    if (!gate.dimension.equals(dimension)) {
      return false;
    }

    int testX = pos.getX();
    int testY = pos.getY();
    int testZ = pos.getZ();

    //    * check if crossingBlock is between gate.origin and gate.farthest (inclusive for lag)
    int minX = gate.origin.getX();
    int minY = gate.origin.getY();
    int minZ = gate.origin.getZ();
    int maxX = gate.farthest.getX();
    int maxY = gate.farthest.getY();
    int maxZ = gate.farthest.getZ();
    boolean fixedX = minX == maxX;
    boolean fixedY = minY == maxY;
    boolean fixedZ = minZ == maxZ;
    boolean insideX = fixedX ? minX == testX : minX <= testX && testX <= maxX;
    boolean insideY = fixedY ? minY == testY : minY <= testY && testY <= maxY;
    boolean insideZ = fixedZ ? minZ == testZ : minZ <= testZ && testZ <= maxZ;
    boolean insideBB = insideX && insideY && insideZ;
    if (!insideBB) {
      return false;
    }

    Vector3f crossingVector = new Vector3f(pos.getX(), pos.getY(), pos.getZ());
    Vector3f originVector = new Vector3f(gate.origin.getX(), gate.origin.getY(), gate.origin.getZ());
    Vector3f originToCrossing = crossingVector.subtract(originVector);
    int row = (int) Math.floor(originToCrossing.dot(gate.up));
    int column = (int) Math.floor(originToCrossing.dot(gate.right));

    //    * figure out if the "column" is between rowMin and rowMax.
    int rowMin = gate.rowMin[row];
    int rowMax = gate.rowMax[row];
    // pass will allow the crossing point to be inside the solid edge blocks
    // in order to give leeway for:
    //   * laggy connection
    //   * non-full size edge blocks, like stairs, gates, poles, etc..
    boolean pass = rowMin <= column && column <= rowMax;
    return pass;
  }

  private static @Nullable RaceGate getNextGate(String userId) {
    if (!checkRacingMode(userId)) return null;
    RaceTrack track = userToTrack.get(userId);
    if (track == null) return null;
    if (track.gates == null) return null;
    Integer gateIndex = userNextGate.get(userId);
    if (gateIndex == null) return null;
    return track.gates.get(gateIndex);
  }

  private static CompletableFuture<Void> handleGatePassed(String userId, @Nullable Entity entity) throws Exception {
    if (!checkRacingMode(userId)) return completedFuture(null);
    RaceTrack track = userToTrack.get(userId);
    if (track == null) return completedFuture(null);
    if (track.gates == null) return completedFuture(null);
    Integer gateIndex = userNextGate.get(userId);
    if (gateIndex == null) return completedFuture(null);
    int maxIndex = track.gates.size() - 1;
    int nextIndex = gateIndex + 1;
    if (nextIndex > maxIndex) {
      nextIndex = 0;
    }
    userNextGate.put(userId, nextIndex);

    // Update client's gateIndex
    if (!Main.isUnitTest) {
      try {
        PacketHandler.sendTo(new GateIndexPacket(nextIndex, RaceBuild.getUUID(track.raceTrackId), RaceBuild.getUUID(userId)), (ServerPlayerEntity) entity);
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }

    if (gateIndex == 0) {
      // first gate passed
      if (userStartTime.get(userId) == null) {
        // start timer for the first time
        long startTime = System.currentTimeMillis();
        userStartTime.put(userId, startTime);
        if (!Main.isArmed(userId)) {
          // only send this message if the user isn't armed.
          Main.sendInfoMessage(entity, "Passed gate " + (gateIndex + 1) + " of " + track.gates.size() + ".");
        }
        if (!Main.isUnitTest) {
          try {
            PacketHandler.sendTo(new LapStartPacket(startTime, RaceBuild.getUUID(userId)), (ServerPlayerEntity) entity);
          } catch (Exception e) {
            Main.LOGGER.error(e.getMessage());
          }
        }
      } else {
        // no need to await because it's just updating the PB.
        await(handleLapComplete(userId, entity));
      }
    } else {
      if (!Main.isArmed(userId)) {
        // only send this message if the user isn't armed.
        Main.sendInfoMessage(entity, "Passed gate " + (gateIndex + 1) + " of " + track.gates.size() + ".");
      }
    }

    return completedFuture(null);
  }

  private static CompletableFuture<Void> handleLapComplete(String userId, @Nullable Entity entity) throws Exception {
    long endMillis = System.currentTimeMillis();
    long startMillis = userStartTime.get(userId);
    long elapsed = endMillis - startMillis;
    userStartTime.put(userId, endMillis);

    if (!Main.isUnitTest) {
      try {
        PacketHandler.sendTo(new LapStartPacket(endMillis, RaceBuild.getUUID(userId)), (ServerPlayerEntity) entity);
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }

    if (elapsed > 16777215) {
      // Took too long.
      Main.sendErrorMessage(entity, "Lap discarded. Took too long.");
      return completedFuture(null);
    }

    RaceTrack track = userToTrack.get(userId);
    if (track == null) {
      return completedFuture(null);
    }

    // todo: put drone build in here.
    JSONObject data = (JSONObject) JSONValue.parse("{}");

    // todo: send this data over packet and display in HUD.
    String message = "Lap time: " + formatTime((int) elapsed);
    if (!Main.isArmed(userId)) {
      // only send this message if the user isn't armed.
      Main.sendSuccessMessage(entity, message);
    }

    if (entity == null) {
      Main.LOGGER.info(message);
    }

    ArrayList<RaceLap> prevTops = await(RaceLap.getBestTimes(track.raceTrackId));
    // store the time.
    await(RaceLap.insertLap(track.raceTrackId, userId, (int) elapsed, data));
    ArrayList<RaceLap> nextTops = await(RaceLap.getBestTimes(track.raceTrackId));
    RaceLap nextBest = await(RaceLap.getSingleBestTime(track.raceTrackId, userId));

    if (!Main.isUnitTest) {
      try {
        // send the best time to everyone else to update client UI.
        PacketHandler.sendToAll(new LapBestPacket(
        nextBest.millis,
        RaceBuild.getUUID(track.raceTrackId),
        RaceBuild.getUUID(userId)
        ));
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }

    if (!Main.isUnitTest) {
      RaceLap prevTop = prevTops.size() > 0 ? prevTops.get(0) : null;
      RaceLap nextTop = nextTops.size() > 0 ? nextTops.get(0) : null;
      if (prevTop == null && nextTop != null) {
        String name = Main.getPlayerNameFromUuid(RaceBuild.getUUID(nextTop.userId).toString());
        Main.sendDiscord(":first_place: " + name + " took the lead!" + "\nhttps://minecraftfpv.com/track/" + track.raceTrackId, Main.discordRaces);
      } else if (!prevTop.userId.equals(nextTop.userId)) {
        String prevName = Main.getPlayerNameFromUuid(RaceBuild.getUUID(prevTop.userId).toString());
        String nextName = Main.getPlayerNameFromUuid(RaceBuild.getUUID(nextTop.userId).toString());
        Main.sendDiscord(":first_place: " + nextName + " took the lead from " + prevName + "!" + "\nhttps://minecraftfpv.com/track/" + track.raceTrackId, Main.discordRaces);
      }
    }

    return completedFuture(null);
  }

  public static String formatTime(int millis) {
    int hours = millis / (1000 * 60 * 60);
    millis -= hours * (1000 * 60 * 60);
    int minutes = millis / (1000 * 60);
    millis -= minutes * (1000 * 60);
    int seconds = millis / 1000;
    millis -= seconds * 1000;
    int tens = millis / 10;
    if (hours > 0) {
      return MessageFormat.format("{0}:{1}:{2}:{3}", hours, minutes, seconds, tens);
    } else if (minutes > 0) {
      return MessageFormat.format("{0}:{1}:{2}", minutes, seconds, tens);
    } else {
      return MessageFormat.format("{0}:{1}", seconds, tens);
    }
  }
}
