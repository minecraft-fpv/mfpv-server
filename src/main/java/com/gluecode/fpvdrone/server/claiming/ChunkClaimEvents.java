package com.gluecode.fpvdrone.server.claiming;

import com.gluecode.fpvdrone.server.Main;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.swing.text.AttributeSet;
import java.util.ArrayList;

@Mod.EventBusSubscriber
public class ChunkClaimEvents {
  public static void handleBlockEvent(Entity entity, BlockEvent event) {
    boolean hasPermission = ChunkClaim.checkPermission(entity.getUUID().toString(), new ChunkPos(event.getPos()), entity.level);
    if (!hasPermission) {
      event.setCanceled(true);
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onBlockBreak(BlockEvent.BreakEvent event) {
    handleBlockEvent(event.getPlayer(), event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
    handleBlockEvent(event.getEntity(), event);
    if (event.isCanceled()) {
      if(event.getEntity() instanceof ServerPlayerEntity) {
        BlockState block = event.getPlacedBlock();
        ((ServerPlayerEntity) event.getEntity()).refreshContainer(((ServerPlayerEntity) event.getEntity()).inventoryMenu);
      }
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onBlockPlaceMultiple(BlockEvent.EntityMultiPlaceEvent event) {
    handleBlockEvent(event.getEntity(), event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onTrampleEvent(BlockEvent.FarmlandTrampleEvent event) {
    handleBlockEvent(event.getEntity(), event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onRightClickEvent(BlockEvent.BlockToolInteractEvent event) {
    handleBlockEvent(event.getPlayer(), event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void onExplosionEvent(ExplosionEvent.Detonate event) {
    Explosion explosion = event.getExplosion();
    if (explosion.getToBlow().isEmpty()) return;
    LivingEntity entity = explosion.getSourceMob();
    World world = event.getWorld();

    ArrayList<BlockPos> list = new ArrayList<>(explosion.getToBlow());
    explosion.clearToBlow();

    if (entity == null) {
      Main.LOGGER.info("null entity explosion");
      for (BlockPos pos : list) {
        boolean isClaimed = ChunkClaim.checkClaimed(new ChunkPos(pos), world);
        if (!isClaimed) {
          explosion.getToBlow().add(pos);
        }
      }
    } else {
      PlayerEntity player = world.getPlayerByUUID(entity.getUUID());
      if (player == null) {
        Main.LOGGER.info("non-player explosion");
        for (BlockPos pos : list) {
          boolean isClaimed = ChunkClaim.checkClaimed(new ChunkPos(pos), world);
          if (!isClaimed) {
            explosion.getToBlow().add(pos);
          }
        }
      } else {
        Main.LOGGER.info("player explosion");
        for (BlockPos pos : list) {
          boolean hasPermission = ChunkClaim.checkPermission(player.getUUID().toString(), new ChunkPos(pos), world);
          if (hasPermission) {
            explosion.getToBlow().add(pos);
          }
        }
      }
    }
  }

  @SubscribeEvent
  public static void onEnteringChunk(EntityEvent.EnteringChunk event) {
    Entity entity = event.getEntity();
    if (entity == null) return;
    World world = entity.level;
    String uuid = entity.getUUID().toString();
    if (world == null || uuid == null) return;

    ChunkPos nextChunk = new ChunkPos(event.getNewChunkX(), event.getNewChunkZ());

    Boolean infoAuto = Main.infoAuto.get(uuid);
    if (infoAuto != null && infoAuto) {
      Main.sendInfoMessage(entity, ChunkClaim.info(entity.getUUID().toString(), nextChunk, entity.level));
    }

    Boolean claimAuto = Main.claimAuto.get(uuid);
    if (claimAuto != null && claimAuto) {
      int size = Main.claimAutoSize.get(uuid);
      for (int x = nextChunk.x - size; x <= nextChunk.x + size; x++) {
        for (int z = nextChunk.z - size; z <= nextChunk.z + size; z++) {
          ChunkPos fragment = new ChunkPos(x, z);
          if (ChunkClaim.claim(uuid, fragment, world)) {
            Main.sendSuccessMessage(entity, fragment.toString() + " claimed!");
          } else {
            if (!ChunkClaim.checkQuota(uuid)) {
              Main.sendErrorMessage(entity, fragment.toString() + "You have reach the maximum amount of claims!");
            } else if (!ChunkClaim.checkOwner(uuid, fragment, world)) {
              Main.sendErrorMessage(entity, fragment.toString() + " claimed by someone else!");
            }
          }
        }
      }
    }

    Boolean unclaimAuto = Main.unclaimAuto.get(uuid);
    if (unclaimAuto != null && unclaimAuto) {
      int size = Main.unclaimAutoSize.get(uuid);
      for (int x = nextChunk.x - size; x <= nextChunk.x + size; x++) {
        for (int z = nextChunk.z - size; z <= nextChunk.z + size; z++) {
          ChunkPos fragment = new ChunkPos(x, z);
          if (ChunkClaim.unclaim(uuid, fragment, world)) {
            Main.sendSuccessMessage(entity, fragment.toString() + " unclaimed!");
          }
        }
      }
    }
  }
}
