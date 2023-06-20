package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.network.PacketHandler;
import com.gluecode.fpvdrone.network.SetBuildModePacket;
import com.gluecode.fpvdrone.race.SerialRaceGate;
import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import com.google.common.collect.Maps;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.tileentity.SignTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class RaceBuild {
  public static final int MAX_GATES_PER_TRACK = 150;

  private static Map<String, Boolean> isBuildingMode = Maps.newConcurrentMap();
  public static Map<String, BlockKey> startingBlock = Maps.newConcurrentMap();
  public static Map<String, RaceGate[]> gateChoices = Maps.newConcurrentMap();
  public static Map<String, ArrayList<RaceGate>> completedGates = Maps.newConcurrentMap();
  public static Map<String, String> trackName = Maps.newConcurrentMap();

  public static void load() {
    RaceDatabase.load();
  }

  public static @Nullable
  String getId(@Nullable Entity entity) {
    if (entity == null) {
      return null;
    }
    return entity.getUUID().toString().replaceAll("-", "").toUpperCase();
  }

  public static @Nullable
  UUID getUUID(@Nullable String id) {
    if (id == null) return null;
    // f3dc0039-b5c5-4f3d-9ac5-55e873a4b6f2
    // F3DC0039 B5C5 4F3D 9AC5 55E873A4B6F2
    String dashed = id.substring(0, 8) +
    "-" +
    id.substring(8, 12) +
    "-" +
    id.substring(12, 16) +
    "-" +
    id.substring(16, 20) +
    "-" +
    id.substring(20);
    return UUID.fromString(dashed);
  }

  public static @Nullable
  ServerPlayerEntity getEntityFromId(@Nullable String id) {
    UUID uuid = getUUID(id);
    if (uuid == null) return null;
    if (Main.server == null) return null;
    return Main.server.getPlayerList().getPlayer(uuid);
  }

  /*
  Check if a user is the owner of the track which starts at `pos`.
  * */
  public static @NotNull
  CompletableFuture<Boolean> checkOwner(String userId, String dimension, BlockPos pos) throws Exception {
    RaceTrack track = await(RaceTrack.getTrack(dimension, pos));
    if (track == null) {
      return completedFuture(false);
    }
    return completedFuture(track.ownerUserId.equals(userId));
  }

  /*
  Check if a user is in building mode
  * */
  public static boolean checkBuildingMode(String userId) {
    Boolean buildingMode = isBuildingMode.get(userId);
    return buildingMode != null && buildingMode;
  }

  public static void startTrack(String userId, String dimension, BlockPos pos, @Nullable Entity entity) {
    BlockKey startKey = new BlockKey(dimension, pos);
    boolean isBuilding = checkBuildingMode(userId);
    if (isBuilding) return; // todo: feedback
    isBuildingMode.put(userId, true);
    startingBlock.put(userId, startKey);
    gateChoices.remove(userId);
    completedGates.put(userId, new ArrayList<>());
    trackName.put(userId, "");

    Main.sendInfoMessage(
    entity,
    "You are now building a track. Right click blocks with stick to mark gates."
    );

    if (entity != null) {
      try {
        PacketHandler.sendTo(new SetBuildModePacket(true), (ServerPlayerEntity) entity);
      } catch (Exception e) {
      }
    }
  }

  public static void exitBuildingMode(String userId, @Nullable Entity entity) {
    // Allow this run even if the user is not in building mode.
    // It doesn't hurt anything, and presents an opportunity to clear memory.
    isBuildingMode.remove(userId);
    startingBlock.remove(userId);
    gateChoices.remove(userId);
    completedGates.remove(userId);
    trackName.remove(userId);

    Main.sendInfoMessage(entity, "You are no longer building a track.");

    if (entity != null) {
      try {
        PacketHandler.sendTo(new SetBuildModePacket(false), (ServerPlayerEntity) entity);
      } catch (Exception e) {
      }
    }
  }

  public static CompletableFuture<Void> setTrackName(String userId, String name, @Nullable Entity entity) throws RaceGateException {
    if (name.equals("")) {
      String message = "Track name cannot be blank.";
      Main.sendErrorMessage(entity, message);
      throw new RaceGateException(message, message);
    }
    String sanitizedName = MySQLHelper.sanitizeKeyboard(name);
    if (!sanitizedName.equals(name)) {
      String message = "Track name can only contain standard keyboard characters. Space and backslash are not allowed.";
      Main.sendErrorMessage(entity, message);
      throw new RaceGateException(message, message);
    }

    try {
      RaceTrack existingTrack = await(RaceTrack.getTrackByName(sanitizedName));
      if (existingTrack != null) {
        Main.sendErrorMessage(entity, "That name is already taken.");
      } else {
        trackName.put(userId, sanitizedName);
      }
      return completedFuture(null);
    } catch (Exception e) {
      throw new RaceGateException(e.getMessage(), e.getMessage());
    }
  }

  /*
  First the player places the target block.
  Then they go around the track, right click 3 blocks out of each gate.
  Finally, they right click on the starting target block again.

  At any time, they could destroy the target block to exit out of buildingMode.

  @param entity - This is null during unit testing.
  @param world - Null during unit testing.
  * */
  public static CompletableFuture<Void> addBlock(String userId, String dimension, BlockPos pos, Direction testFace, BiPredicate<String, BlockPos> checkSolid, @Nullable Entity entity) throws Exception {
    if (testFace == null) return completedFuture(null);

    BlockKey blockKey = new BlockKey(dimension, pos);

    boolean isBuilding = checkBuildingMode(userId);
    if (!isBuilding)
      throw new Exception("Expected user to be in isBuilding mode.");

    ArrayList<RaceGate> currentCompletedGates = completedGates.get(userId);

    BlockKey startKey = startingBlock.get(userId);
    if (startKey == null) throw new Exception("Unable to find startingPos.");

    // Check if added block is back to the starting block.
    // This should mean the target block was right clicked again.
    if (startKey.equals(blockKey)) {
      await(completeTrack(userId, entity));
      return completedFuture(null);
    }

    if (currentCompletedGates.size() >= MAX_GATES_PER_TRACK) {
      String message = "The max number of gates has been reached (" +
      MAX_GATES_PER_TRACK +
      ").";
      Main.sendErrorMessage(entity, message);
      throw new Exception(message);
    }

    boolean isSolid = checkSolid.test(blockKey.dimension, blockKey.pos);
    if (!isSolid) {
      String message = "Gate block must be solid.";
      Main.sendErrorMessage(entity, message);
      throw new Exception(message);
    }

    // If there are stored choices, then the clicked block is being used to choose one.
    RaceGate[] storedChoices = gateChoices.get(userId);
    if (storedChoices != null) {
      if (storedChoices.length != 2) {
        throw new Exception("Sorry. There is a bug. Please report this.");
      }

      // check if the clicked block is in each stored choice:
      boolean found1 = false;
      for (int i = 0; i < storedChoices[0].path.size(); i++) {
        BlockPos testPos = storedChoices[0].path.get(i);
        if (pos.equals(testPos)) {
          found1 = true;
          break;
        }
      }
      boolean found2 = false;
      for (int i = 0; i < storedChoices[1].path.size(); i++) {
        BlockPos testPos = storedChoices[1].path.get(i);
        if (pos.equals(testPos)) {
          found2 = true;
          break;
        }
      }
      if ((found1 && found2) || (!found1 && !found2)) {
        gateChoices.remove(userId); // allow user to restart from beginning.
        String message = "The block you chose does not specify which gate you want to add.";
        Main.sendErrorMessage(entity, message);
        throw new Exception(message);
      } else if (found1) {
        currentCompletedGates.add(storedChoices[0]);
      } else if (found2) {
        currentCompletedGates.add(storedChoices[1]);
      }
      Main.sendSuccessMessage(entity, "Gate successfully added.");
      RaceGate gate = currentCompletedGates.get(currentCompletedGates.size() - 1);
      sendSerialRaceGate(gate, entity);
      gateChoices.remove(userId);
      return completedFuture(null);
    }

    // Otherwise, there are no stored choices:

    ArrayList<BlockPos> neighbors = findSolidNeighbors(pos,
    testFace,
    dimension,
    checkSolid
    );
    if (neighbors.size() == 0) {
      String message = "Either you selected the wrong face, or this is not a gate.";
      Main.sendErrorMessage(entity, message);
      throw new Exception(message);
    } else if (neighbors.size() > 2) {
      String message = "Found too many neighbors. Please report this bug.";
      Main.sendErrorMessage(entity, message);
      Exception e = new Exception(message);
      throw e;
    }

    BlockPos a = pos;
    Direction face = testFace;

    if (neighbors.size() == 1) {
      try {
        RaceGate gate = RaceMath.loadGate(dimension, a, face, neighbors.get(0), checkSolid, null);
        currentCompletedGates.add(gate);
        sendSerialRaceGate(gate, entity);
        Main.sendSuccessMessage(entity, "Gate successfully added.");
      } catch (RaceGateException e) {
        Main.sendErrorMessage(entity, "Failed to add a gate. " + e.endUserReason);
        throw e;
      }
    } else if (neighbors.size() == 2) {
      RaceGate gate1 = null;
      RaceGate gate2 = null;
      RaceGateException e1 = null;
      RaceGateException e2 = null;
      try {
        gate1 = RaceMath.loadGate(dimension, a, face, neighbors.get(0), checkSolid, null);
      } catch (RaceGateException e) {
        e1 = e;
      }
      try {
        gate2 = RaceMath.loadGate(dimension, a, face, neighbors.get(1), checkSolid, null);
      } catch (RaceGateException e) {
        e2 = e;
      }

      if (gate1 != null && gate2 == null) {
        currentCompletedGates.add(gate1);
        Main.sendSuccessMessage(entity, "Gate successfully added.");
        sendSerialRaceGate(gate1, entity);
      } else if (gate2 != null && gate1 == null) {
        currentCompletedGates.add(gate2);
        Main.sendSuccessMessage(entity, "Gate successfully added.");
        sendSerialRaceGate(gate2, entity);
      } else if (gate1 != null && gate2 != null) {
        RaceGate[] choices = new RaceGate[2];
        choices[0] = gate1;
        choices[1] = gate2;
        gateChoices.put(userId, choices);
        String message = "Two possible gates were found. Choose another block to specify which one you want to add.";
        Main.sendErrorMessage(entity, message);
        throw new Exception(message);
      } else {
        String reason = "";
        if (!e1.endUserReason.equals("") && !e2.endUserReason.equals("")) {
          reason = "Either: " + e1.endUserReason + " OR " + e2.endUserReason;
        } else if (!e1.endUserReason.equals("")) {
          reason = e1.endUserReason;
        } else if (!e2.endUserReason.equals("")) {
          reason = e2.endUserReason;
        }
        Main.sendErrorMessage(entity, "Failed to add a gate. " + reason);
        throw new Exception(reason);
      }
    }

    return completedFuture(null);
  }

  /*
  This is a helper function which should be called from forge code and
  then the forge code should call setTrackName.
  * */
  public static String getTrackName(String userId, World world, Entity entity) {
    BlockKey startKey = startingBlock.get(userId);
    if (startKey == null) return null;

    ArrayList<BlockPos> possibleSignPos = new ArrayList<>();
    possibleSignPos.add(startKey.pos.offset(new BlockPos(1, 0, 0)));
    possibleSignPos.add(startKey.pos.offset(new BlockPos(-1, 0, 0)));
    possibleSignPos.add(startKey.pos.offset(new BlockPos(0, 0, 1)));
    possibleSignPos.add(startKey.pos.offset(new BlockPos(0, 0, -1)));
    possibleSignPos.add(startKey.pos.offset(new BlockPos(0, 1, 0)));

    SignTileEntity sign = null;
    for (BlockPos pos : possibleSignPos) {
      TileEntity tile = world.getBlockEntity(pos);
      if (tile instanceof SignTileEntity) {
        if (sign == null) {
          sign = (SignTileEntity) tile;
        } else {
          Main.sendErrorMessage(entity,
          "There must be exactly 1 sign attached."
          );
          return null;
        }
      }
    }

    if (sign == null) {
      Main.sendErrorMessage(entity, "There must be exactly 1 sign attached.");
      return null;
    }

    // todo: SignTileEntity is private
    return null;
  }

  public static CompletableFuture<Void> completeTrack(String userId, @Nullable Entity entity) throws Exception {
    boolean isBuilding = checkBuildingMode(userId);
    if (!isBuilding) return completedFuture(null);
    BlockKey startKey = startingBlock.get(userId);
    ArrayList<RaceGate> currentCompletedGates = completedGates.get(userId);
    String name = trackName.get(userId);

    if (currentCompletedGates.size() < 2) {
      String message = "The track must contain at least 2 gates.";
      Main.sendErrorMessage(entity, message);
      throw new Exception(message);
    }
  
    if (name.equals("")) {
      String message = "The track is unnamed. Set it using /race setName <name>.";
      Main.sendErrorMessage(entity, message);
      throw new Exception(message);
    }

    // Building modes must be exited before any await statement in order to avoid double submission.
    exitBuildingMode(userId, entity);

    // store track in DB.
    String raceTrackId = await(RaceTrack.insertTrack(userId,
    name,
    startKey.dimension,
    startKey.pos
    ));

    // store gates in DB.
    for (int i = 0; i < currentCompletedGates.size(); i++) {
      // todo: batch insert
      await(RaceGate.insertGate(raceTrackId, i, currentCompletedGates.get(i)));
    }

    Main.sendSuccessMessage(entity, "Track '" + name + "' successfully built!");
    if (entity != null) {
      String playerName = Main.getPlayerNameFromUuid(entity.getUUID().toString());
      Main.sendDiscord(":checkered_flag: " + playerName + " built a track!" + "\nhttps://minecraftfpv.com/track/" + raceTrackId, Main.discordRaces);
    }
    return completedFuture(null);
  }

  public static CompletableFuture<Void> removeTrack(String userId, String dimension, BlockPos pos, @Nullable Entity entity) throws Exception {
    // Only exitBuildingMode if the broken block is the currently creating track.
    boolean isBuildingMode = RaceBuild.checkBuildingMode(userId);
    BlockKey startingBlock = RaceBuild.startingBlock.get(userId);
    if (isBuildingMode &&
    pos.equals(startingBlock.pos) &&
    dimension.equals(startingBlock.dimension)) {
      exitBuildingMode(userId, entity);
    }

    // todo: ownership checking should be done by the chunk claiming module.
    RaceTrack track = await(RaceTrack.getTrack(dimension, pos));
    if (track == null || track.deleted) return completedFuture(null);
//    if (!track.ownerUserId.equals(userId)) {
//      // only owner can delete
//      return completedFuture(null);
//    };

    await(RaceTrack.softDelete(dimension, pos));
    await(RaceGate.softDelete(track.raceTrackId));

    // Remove the breaker from the race first:
    RaceNavigate.exitRacingMode(userId, entity, null);

    // Kick out all the other racers
    String reason = null;
    if (entity != null) {
      reason = "The track starting point was removed by " +
      entity.getName().getString();
      Main.sendDiscord(":x: " + entity.getName().getString() + " deleted the track `" + track.name + "`!"
      + "\nLocation: (" + track.startPosX + ", " + track.startPosY + ", " + track.startPosZ + ")", Main.discordRaces);
    }
    RaceNavigate.exitAllPlayersFromTrack(new BlockKey(track.dimension,
    new BlockPos(track.startPosX, track.startPosY, track.startPosZ)
    ), reason);

    if (entity != null && userId.equals(track.ownerUserId)) {
      Main.sendSuccessMessage(entity,
      "Your track '" + track.name + "' was removed."
      );
    }

    return completedFuture(null);
  }

  private static ArrayList<BlockPos> findSolidNeighbors(BlockPos pos, Direction face, String dimension, BiPredicate<String, BlockPos> checkSolid) {
    // We know that in valid gates, all the blocks at touching either face to face or edge to edge.
    // Corner touches are not valid.
    // So there are 18 possible valid neighbors.

    // A block in a valid gate path should have only 2 neighbors.

    // If the block has 0 or 1 neighbors, it's definitely an error.
    // If the block has 2 or more neighbors, we need to sort the neighbors into planes with the air block designated by the face.
    // Any planes that have less than 2 neighbors are rejected.
    // If there is just 1 plane remaining, then any neighbor in that plane can be selected.
    // If there are 2 or more planes remaining, then a helper block will need to be selected to determine which plane to use.

    // There is a special case for the neighbor which is opposite the face.
    // It should be added to both planes.
    // BTW, this could happen in a valid gate.
    // Consider:
    /*
        xxx
      xx   xx
      x     x
      xx  fpn
        xxx
    * */
    // If the face is selected at `f` on block `p`, a valid neighbor can be found at `n`.


//    BlockPos air = pos.offset(face);


    // Figure out local up and right directions
    Direction up;
    Direction right;
    Direction down;
    Direction left;
//    Direction butt = face.getOpposite();
    if (face.equals(Direction.UP) || face.equals(Direction.DOWN)) {
      up = Direction.SOUTH;
      right = Direction.EAST;
    } else if (face.equals(Direction.NORTH) || face.equals(Direction.SOUTH)) {
      up = Direction.UP;
      right = Direction.EAST;
    } else {
      up = Direction.UP;
      right = Direction.SOUTH;
    }
    down = up.getOpposite();
    left = right.getOpposite();

    // Indices 0, 1, 2 denote tiers:
    //   * Touching air block face to face and solid block edge to edge.
    //   * Touching solid block face to face and air block edge to edge
    //   * Not touching air block at all but touching solid block edge to edge
    BlockPos[] possibleUp = new BlockPos[2];
    possibleUp[0] = pos.relative(up).relative(face);
    possibleUp[1] = pos.relative(up);
//    possibleUp[2] = pos.offset(up).offset(butt);

    BlockPos[] possibleRight = new BlockPos[2];
    possibleRight[0] = pos.relative(right).relative(face);
    possibleRight[1] = pos.relative(right);
//    possibleRight[2] = pos.offset(right).offset(butt);

    BlockPos[] possibleDown = new BlockPos[2];
    possibleDown[0] = pos.relative(down).relative(face);
    possibleDown[1] = pos.relative(down);
//    possibleDown[2] = pos.offset(down).offset(butt);

    BlockPos[] possibleLeft = new BlockPos[2];
    possibleLeft[0] = pos.relative(left).relative(face);
    possibleLeft[1] = pos.relative(left);
//    possibleLeft[2] = pos.offset(left).offset(butt);
    
    /*
      Note:
      The lowest tier (index 2) is not considers because we are only accepting convex gates.
      Likewise, the butt block is not acceptable.
    * */

    // For each direction, we will take the solid block in the highest tier.
    BlockPos topUp = null;
    BlockPos topRight = null;
    BlockPos topDown = null;
    BlockPos topLeft = null;
    for (int i = 0; i < 2; i++) {
      if (topUp == null && checkSolid.test(dimension, possibleUp[i])) {
        topUp = possibleUp[i];
      }
      if (topRight == null && checkSolid.test(dimension, possibleRight[i])) {
        topRight = possibleRight[i];
      }
      if (topDown == null && checkSolid.test(dimension, possibleDown[i])) {
        topDown = possibleDown[i];
      }
      if (topLeft == null && checkSolid.test(dimension, possibleLeft[i])) {
        topLeft = possibleLeft[i];
      }
    }

    // Gather only 1 block per plane:
    ArrayList<BlockPos> result = new ArrayList<>();
    if (topUp != null || topDown != null) {
      result.add(topUp != null ? topUp : topDown);
    }
    if (topRight != null || topLeft != null) {
      result.add(topRight != null ? topRight : topLeft);
    }

    // If all of the above were null, then maybe the butt block is solid?
//    if (topUp == null && topRight == null && topDown == null && topLeft == null) {
//      BlockPos buttBlockPos = pos.offset(butt);
//      if (checkSolid.test(dimension, buttBlockPos)) {
//        result.add(buttBlockPos);
//      }
//    }

    return result;
  }

  public static void sendSerialRaceGate(RaceGate gate, @Nullable Entity entity) {
    if (entity != null) {
      try {
        PacketHandler.sendTo(new SerialRaceGate(
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
        ), (ServerPlayerEntity) entity);
      } catch (Exception e) {
      }
    }
  }
}
