package com.gluecode.fpvdrone.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.CreatureEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SPlayerPositionLookPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.server.TicketType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@OnlyIn(Dist.DEDICATED_SERVER)
public class FpvCommand {
  private static final SimpleCommandExceptionType FAILED_EXCEPTION = new SimpleCommandExceptionType(new StringTextComponent(
  "Unable to parse command."));

  public static void register(CommandDispatcher<CommandSource> dispatcher) {
    LiteralArgumentBuilder<CommandSource> fpv = Commands.literal("fpv");

    fpv.executes(FpvCommand::fpv);

    // 'fpv gamemode `mode`' commands:
    for (GameType gametype : GameType.values()) {
      if (gametype != GameType.NOT_SET) {
        fpv.then(Commands.literal("gamemode").then(Commands.literal(gametype.getName()).executes((context) -> {
          return fpv_gamemode(context, gametype);
        })));
      }
    }

    // fpv spawn command:
    fpv.then(Commands.literal("spawn").executes(FpvCommand::fpv_spawn));

    fpv.then(Commands.literal("spectate").executes((context) -> {
      return fpv_spectate(context, null, context.getSource().getPlayerOrException());
    }).then(Commands.argument("target", EntityArgument.player()).executes((context) -> {
      return fpv_spectate(context, EntityArgument.getEntity(context, "target"), context.getSource().getPlayerOrException());
    })));

    dispatcher.register(fpv);
  }

  private static int fpv(CommandContext<CommandSource> context) {
    context.getSource().sendSuccess(new StringTextComponent("Welcome to Minecraft FPV."), false);
    return 1;
  }

  private static int fpv_gamemode(CommandContext<CommandSource> context, GameType gametype) throws CommandSyntaxException {
    return setGameMode(context, Collections.singleton(context.getSource().getPlayerOrException()), gametype);
  }

  private static int fpv_spawn(CommandContext<CommandSource> context) throws CommandSyntaxException {
    CommandSource source = context.getSource();
    ServerWorld world = source.getLevel();
    int spawnX = world.getLevelData().getXSpawn();
    int spawnY = world.getLevelData().getYSpawn();
    int spawnZ = world.getLevelData().getZSpawn();
    Set<SPlayerPositionLookPacket.Flags> set = EnumSet.noneOf(SPlayerPositionLookPacket.Flags.class);
    teleport(source, source.getPlayerOrException(), world, spawnX + 0.5, spawnY, spawnZ + 0.5, set, 0, 0);
    return 1;
  }

  private static int fpv_spectate(CommandContext<CommandSource> context, @Nullable Entity target, ServerPlayerEntity player) throws CommandSyntaxException {
    CommandSource source = context.getSource();

    if (player == target) {
      throw (new SimpleCommandExceptionType(new StringTextComponent("You cannot spectate yourself."))).create();
    }

    if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
      setGameMode(context, Collections.singleton(source.getPlayerOrException()), GameType.SPECTATOR);
    }

    player.setCamera(target); // setSpectatingEntity
    if (target != null) {
      source.sendSuccess(
      new TranslationTextComponent("commands.spectate.success.started", target.getDisplayName()),
      false
      );
    } else {
      source.sendSuccess(new TranslationTextComponent("commands.spectate.success.stopped"), false);
    }

    return 1;
  }

  public static void sendGameModeFeedback(CommandSource source, ServerPlayerEntity player, GameType gameTypeIn) {
    ITextComponent itextcomponent = new TranslationTextComponent("gameMode." + gameTypeIn.getName());
    if (source.getEntity() == player) {
      source.sendSuccess(new TranslationTextComponent("commands.gamemode.success.self", itextcomponent), true);
    } else {
      if (source.getLevel().getGameRules().getBoolean(GameRules.RULE_SENDCOMMANDFEEDBACK)) {
        player.sendMessage(new TranslationTextComponent("gameMode.changed", itextcomponent), Util.NIL_UUID);
      }

      source.sendSuccess(new TranslationTextComponent(
      "commands.gamemode.success.other",
      player.getDisplayName(),
      itextcomponent
      ), true);
    }

  }

  public static int setGameMode(CommandContext<CommandSource> context, Collection<ServerPlayerEntity> players, GameType gameTypeIn) {
    int i = 0;

    for (ServerPlayerEntity serverplayerentity : players) {
      if (serverplayerentity.gameMode.getGameModeForPlayer() != gameTypeIn) {
        serverplayerentity.setGameMode(gameTypeIn);
        sendGameModeFeedback(context.getSource(), serverplayerentity, gameTypeIn);
        ++i;
      }
    }

    return i;
  }

  public static void teleport(CommandSource source, Entity entityIn, ServerWorld worldIn, double x, double y, double z, Set<SPlayerPositionLookPacket.Flags> relativeList, float yaw, float pitch) throws CommandSyntaxException {
    BlockPos blockpos = new BlockPos(x, y, z);
    if (!World.isInSpawnableBounds(blockpos)) {
      throw FAILED_EXCEPTION.create();
    } else {
      if (entityIn instanceof ServerPlayerEntity) {
        ChunkPos chunkpos = new ChunkPos(new BlockPos(x, y, z));
        worldIn.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, entityIn.getId());
        entityIn.stopRiding();
        if (((ServerPlayerEntity) entityIn).isSleeping()) {
          ((ServerPlayerEntity) entityIn).stopSleepInBed(true, true);
        }

        if (worldIn == entityIn.level) {
          ((ServerPlayerEntity) entityIn).connection.teleport(x, y, z, yaw, pitch, relativeList);
        } else {
          ((ServerPlayerEntity) entityIn).teleportTo(worldIn, x, y, z, yaw, pitch);
        }

        entityIn.setYHeadRot(yaw);
      } else {
        float f1 = MathHelper.wrapDegrees(yaw);
        float f = MathHelper.wrapDegrees(pitch);
        f = MathHelper.clamp(f, -90.0F, 90.0F);
        if (worldIn == entityIn.level) {
          entityIn.moveTo(x, y, z, f1, f);
          entityIn.setYHeadRot(f1);
        } else {
          entityIn.unRide();
          Entity entity = entityIn;
          entityIn = entityIn.getType().create(worldIn);
          if (entityIn == null) {
            return;
          }

          entityIn.restoreFrom(entity);
          entityIn.moveTo(x, y, z, f1, f);
          entityIn.setYHeadRot(f1);
          worldIn.addFromAnotherDimension(entityIn);
        }
      }

      if (!(entityIn instanceof LivingEntity) || !((LivingEntity) entityIn).isFallFlying()) {
        entityIn.setDeltaMovement(entityIn.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
        entityIn.setOnGround(true);
      }

      if (entityIn instanceof CreatureEntity) {
        ((CreatureEntity) entityIn).getNavigation().stop();
      }

    }
  }
}
