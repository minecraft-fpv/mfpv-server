package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.claiming.ChunkClaim;
import com.jme3.math.Vector3f;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

/*
All SQL queries should happen only after checking if the block is a target block.

Stories:

## User Creates Track
* Place target block
* Right click set of 3 block to mark a gate.
* Right click the target block to complete the track.
* Calling /race name <value> will name the last complete, un-named track.

## Owner Destroys Target Block
* The track is deleted.

## Non-owner tries to destroy target block.
* The track is deleted.
* Owners should use the chunk claim mod to protect their track.

## Anyone destroys a gate.
* The track is deleted.
* Owners should use the chunk claim mod to protect their track.

## Entering a race.
* Right click a registered target block.
* The track is loaded, if it isn't, which means:
  * verifying gates
  * pre-computing gate parameters

## Exiting a race.
* Right click the target block again.
* Automatic exit after 1 minutes of no gate progress.

## Passing through a gate.
* Player must be in racing mode.

* */

@Mod.EventBusSubscriber
public class RaceEvents {
  private static final HashMap<Integer, Long> lastRightClickTime = new HashMap<>();

  public static boolean isTargetBlock(Block block) {
    ResourceLocation location = block.getRegistryName();
    if (location == null) {
      return false;
    }
    return location.toString().equalsIgnoreCase("minecraft:target");
  }

  @SubscribeEvent
  public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
    // Event may be cancelled by grief protection
    if (event.isCanceled()) return;

    Block block = event.getPlacedBlock().getBlock();
    String userId = RaceBuild.getId(event.getEntity());
    String dimension = Main.getDimension(event.getEntity().getCommandSenderWorld());
    boolean isTargetBlock = isTargetBlock(block);
    if (isTargetBlock) {

      RaceBuild.startTrack(userId,
      dimension,
      event.getPos(),
      event.getEntity()
      );
    }
  }

  @SubscribeEvent
  public static void onRightClickBlockSubscriber(PlayerInteractEvent.RightClickBlock event) {
    Entity entity = event.getEntity();
    World world = event.getWorld();
    BlockPos pos = event.getPos();
    String userId = RaceBuild.getId(entity);
    String dimension = Main.getDimension(world);
    int hashCode = RaceRightClickEvent.hashCode(userId, dimension, pos);
    Long time = lastRightClickTime.get(hashCode);
    if (time != null && System.currentTimeMillis() - time < 200) {
      // The amount of time that has passed since right clicking the same block is less than 100ms.
      return;
    }
    lastRightClickTime.put(hashCode, System.currentTimeMillis());
    // todo: remove old hashCodes

    // todo: respect chunk claiming rules
    CompletableFuture.runAsync(() -> onRightClickBlockAsync(event));
  }

  public static CompletableFuture<Void> onRightClickBlockAsync(PlayerInteractEvent.RightClickBlock event) {
    // Event may be cancelled by grief protection
    if (event.isCanceled()) return completedFuture(null);

    Entity entity = event.getEntity();
    World world = event.getWorld();
    BlockPos pos = event.getPos();
    Block block = world.getBlockState(pos).getBlock();
    boolean isTargetBlock = isTargetBlock(block);

    boolean isStick = false;
    try {
      ItemStack itemStack = ((PlayerEntity) entity).getMainHandItem();
      String itemId = itemStack.getItem().toString();
      isStick = itemId.equals("stick");
    } catch (Exception e) {
      Main.LOGGER.error(e.getMessage());
    }

    // inputs:
    String userId = RaceBuild.getId(entity);
    String dimension = Main.getDimension(world);
    BiPredicate<String, BlockPos> checkSolid = (String testDimension, BlockPos testPos) -> {
      BlockState blockState = world.getBlockState(testPos);
      return blockState.getMaterial().isSolid() &&
      testDimension.equals(dimension);
    };

    try {
      if (isStick && RaceBuild.checkBuildingMode(userId)) {
        // User is in building mode and right clicking with a stick.

//        // Check if the user right clicked the starting target block,
//        // or a sign attached to the starting target block:
//        BlockKey currentKey = new BlockKey(dimension, pos);
//        BlockKey attachedTargetKey = attachedTargetBlockPos !=
//        null ? new BlockKey(dimension, attachedTargetBlockPos) : null;
//        BlockKey startKey = RaceBuild.startingBlock.get(userId);
//        if (startKey != null &&
//        (startKey.equals(currentKey) || startKey.equals(attachedTargetKey))) {
//          await(RaceBuild.completeTrack(userId, entity));
//          return completedFuture(null);
//        } else {
//
//        }
        // The player will add a block to the track they are building.
        await(RaceBuild.addBlock(userId, dimension, pos, event.getFace(), checkSolid, entity));
      } else if (isTargetBlock &&
        await(RaceNavigate.checkTrackStart(dimension, pos))) {
          // The player will enter or exit racing mode.
          Vector3f playerPos = new Vector3f(
            (float) entity.getX(),
            (float) entity.getY(),
            (float) entity.getZ()
          );
          await(RaceNavigate.handleRightClickTrack(userId,
          dimension,
          pos,
          checkSolid,
          playerPos,
          entity
        ));

        Main.server.getScoreboard();
      }
    } catch (Exception e) {
      Main.LOGGER.error(e.getMessage());
    }

    return completedFuture(null);
  }

  @SubscribeEvent
  public static void onBlockBreakSubscriber(BlockEvent.BreakEvent event) {
    // Event may be cancelled by grief protection
    if (event.isCanceled()) return;
    CompletableFuture.runAsync(() -> onBlockBreakAsync(event));
  }

  public static CompletableFuture<Void> onBlockBreakAsync(BlockEvent.BreakEvent event) {
    // Event may be cancelled by grief protection
    if (event.isCanceled()) return completedFuture(null);

    Block block = event.getState().getBlock();
    World world = event.getPlayer().getCommandSenderWorld();
    String dimension = Main.getDimension(world);


    boolean isTargetBlock = isTargetBlock(block);

    try {
      if (isTargetBlock) {
        // todo: the event cannot be cancelled inside an async method.
        // So we need a different way of preventing track deletion.
        String userId = RaceBuild.getId(event.getPlayer());
        // remove Track will check ownership and
        // also take the user out of edit mode if they are in edit mode.
        await(RaceBuild.removeTrack(userId,
        dimension,
        event.getPos(),
        event.getPlayer()
        ));
      }
    } catch (Exception e) {
      Main.sendErrorMessage(event.getPlayer(), e.getMessage());
      Main.LOGGER.error(e.getMessage());
    }

    return completedFuture(null);
  }

  @SubscribeEvent
  public static void onPlayerMoved(TickEvent.PlayerTickEvent event) {
    if (event.phase != TickEvent.Phase.END) return;
    PlayerEntity entity = event.player;
    String userId = RaceBuild.getId(entity);
    String dimension = Main.getDimension(entity.getCommandSenderWorld());
    if (RaceNavigate.checkRacingMode(userId)) {
      Vector3f pos = new Vector3f((float) entity.getX(),
      (float) entity.getY(),
      (float) entity.getZ()
      );
      try {
        RaceNavigate.onPlayerMoved(userId, dimension, pos, entity);
      } catch (Exception e) {
        Main.LOGGER.error(e.getMessage());
      }
    }
  }

  @SubscribeEvent
  public static void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
    PlayerEntity entity = event.getPlayer();
    String userId = RaceBuild.getId(entity);
    RaceBuild.exitBuildingMode(userId, entity);
    RaceNavigate.exitRacingMode(userId, entity, null);
  }
}
