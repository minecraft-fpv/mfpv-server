package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.claiming.ChunkClaim;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

@Mod.EventBusSubscriber
public class RaceGateChangeEvents {
  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onBlockBreak(BlockEvent.BreakEvent event) {
    if (!event.isCanceled()) {
      String dimension = Main.getDimension(event.getPlayer().getCommandSenderWorld());
      PlayerEntity entity = event.getPlayer();
      String entityName = entity == null ? "unknown entity" : entity.getName().getString();
      RaceNavigate.changedGateBlock(dimension, event.getPos(), "A gate was changed by " + entityName);
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
    if (!event.isCanceled()) {
      String dimension = Main.getDimension(event.getEntity().getCommandSenderWorld());
      Entity entity = event.getEntity();
      String entityName = entity == null ? "unknown entity" : entity.getName().getString();
      RaceNavigate.changedGateBlock(dimension, event.getPos(), "A gate was changed by " + entityName);
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onBlockPlaceMultiple(BlockEvent.EntityMultiPlaceEvent event) {
    if (!event.isCanceled()) {
      String dimension = Main.getDimension(event.getEntity().getCommandSenderWorld());
      Entity entity = event.getEntity();
      String entityName = entity == null ? "unknown entity" : entity.getName().getString();
      RaceNavigate.changedGateBlock(dimension, event.getPos(), "A gate was changed by " + entityName);
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onExplosionEvent(ExplosionEvent.Detonate event) {
    /*
    This should run after chunk claim has modified the list of affected block positions.
    * */

    Explosion explosion = event.getExplosion();
    if (explosion.getToBlow().isEmpty()) return;
    ArrayList<BlockPos> list = new ArrayList<>(explosion.getToBlow());
    World world = event.getWorld();
    String dimension = Main.getDimension(world);
    LivingEntity entity = explosion.getSourceMob();
    
    handleExplosion(dimension, list, entity);
  }

  public static CompletableFuture<Void> handleExplosion(String dimension, ArrayList<BlockPos> list, @Nullable Entity entity) {
    String reason = entity == null ? "A gate was changed by explosion" : "A gate was changed by " + entity.getName().getString();
    
    // todo: this loop is very expensive
    for (BlockPos pos : list) {
      await(RaceNavigate.changedGateBlock(dimension, pos, reason));
    }
    return completedFuture(null);
  }
}
