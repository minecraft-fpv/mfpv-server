package com.gluecode.fpvdrone.server.claiming;

import com.gluecode.fpvdrone.server.Main;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.command.arguments.EntitySelector;
import net.minecraft.command.arguments.GameProfileArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.StringTextComponent;

import java.util.Collection;

public class ChunkCommand {
  private static final SimpleCommandExceptionType FAILED_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("Unable to parse command."));
  private static final SimpleCommandExceptionType PERMISSION_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("You do not have chunk permission."));
  private static final SimpleCommandExceptionType TOO_MANY_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("Only a single entity can be targeted at a time."));

  public static void register(CommandDispatcher<CommandSource> dispatcher) {
    LiteralArgumentBuilder<CommandSource> chunk = Commands.literal("chunk");

    chunk.executes(ChunkCommand::help);
    chunk.then(Commands.literal("info").executes(ChunkCommand::info).then(
    Commands.literal("auto").executes(ChunkCommand::infoAuto)));
    chunk.then(Commands.literal("help").executes(ChunkCommand::help));
    chunk.then(Commands.literal("claim").executes(ChunkCommand::claim).then(
    Commands.literal("auto").executes(ChunkCommand::claimAutoStop).then(Commands.argument(
    "size",
    IntegerArgumentType.integer(0, 2)
    ).executes(ChunkCommand::claimAuto))));
    chunk.then(Commands.literal("unclaim").executes(ChunkCommand::unclaim).then(
    Commands.literal("auto").executes(ChunkCommand::unclaimAutoStop).then(
    Commands.argument("size", IntegerArgumentType.integer(0, 2)).executes(
    ChunkCommand::unclaimAuto))));
    chunk.then(Commands.literal("list").executes(ChunkCommand::list));
    chunk.then(Commands.literal("permit").then(Commands.argument(
    "user",
    GameProfileArgument.gameProfile()
    ).executes(ChunkCommand::permit)));
    chunk.then(Commands.literal("unpermit").then(Commands.argument(
    "user",
    GameProfileArgument.gameProfile()
    ).executes(ChunkCommand::unpermit)));
    chunk.then(Commands.literal("transfer").then(Commands.argument(
    "user",
    GameProfileArgument.gameProfile()
    ).executes(ChunkCommand::transfer)));

    dispatcher.register(chunk);

    // todo: toggle mode: on EnteringChunk event, tell player when they enter or leave a claim region.
  }

  private static int info(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    ChunkPos pos = new ChunkPos(player.xChunk, player.zChunk);

    context.getSource().sendSuccess(new StringTextComponent(ChunkClaim.info(player.getUUID().toString(),
    pos,
    player.level
    )), false);
    return 1;
  }

  private static int infoAuto(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String uuid = player.getUUID().toString();

    Boolean current = Main.infoAuto.get(uuid);
    if (current == null) {
      Main.infoAuto.put(uuid, true);
    } else {
      Main.infoAuto.put(uuid, !current);
    }
    current = Main.infoAuto.get(uuid);

    if (Main.claimAuto.get(uuid) != null && Main.claimAuto.get(uuid)) {
      Main.claimAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk claim: disabled"), false);
    }

    if (Main.unclaimAuto.get(uuid) != null && Main.unclaimAuto.get(uuid)) {
      Main.unclaimAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk unclaim: disabled"), false);
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Automatic chunk info: " + (current ? "enabled" : "disabled")), false);
    return 1;
  }

  private static int claimAuto(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String uuid = player.getUUID().toString();
    int size = IntegerArgumentType.getInteger(context, "size");

    Main.claimAuto.put(uuid, true);
    Main.claimAutoSize.put(uuid, size);

    String aoe = "1 x 1 area";
    if (size == 0) {
      aoe = "1 x 1 area";
    } else if (size == 1) {
      aoe = "3 x 3 area";
    } else if (size == 2) {
      aoe = "5 x 5 area";
    }

    if (Main.infoAuto.get(uuid) != null && Main.infoAuto.get(uuid)) {
      Main.infoAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk info: disabled"), false);
    }

    if (Main.unclaimAuto.get(uuid) != null && Main.unclaimAuto.get(uuid)) {
      Main.unclaimAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk unclaim: disabled"), false);
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Automatic chunk claim: enabled " + aoe +
    "\nDisable using /chunk claim auto"), false);
    return 1;
  }

  private static int unclaimAuto(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String uuid = player.getUUID().toString();
    int size = IntegerArgumentType.getInteger(context, "size");

    Main.unclaimAuto.put(uuid, true);
    Main.unclaimAutoSize.put(uuid, size);

    String aoe = "1 x 1 area";
    if (size == 0) {
      aoe = "1 x 1 area";
    } else if (size == 1) {
      aoe = "3 x 3 area";
    } else if (size == 2) {
      aoe = "5 x 5 area";
    }

    if (Main.infoAuto.get(uuid) != null && Main.infoAuto.get(uuid)) {
      Main.infoAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk info: disabled"), false);
    }

    if (Main.claimAuto.get(uuid) != null && Main.claimAuto.get(uuid)) {
      Main.claimAuto.put(uuid, false);
      context.getSource().sendSuccess(new StringTextComponent(
      "Automatic chunk claim: disabled"), false);
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Automatic chunk unclaim: enabled " + aoe +
    "\nDisable using /chunk unclaim auto"), false);
    return 1;
  }

  private static int claimAutoStop(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String uuid = player.getUUID().toString();

    Boolean current = Main.claimAuto.get(uuid);
    if (current != null && current) {
      Main.claimAuto.put(uuid, false);
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Automatic chunk claim: disabled"), false);
    return 1;
  }

  private static int unclaimAutoStop(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String uuid = player.getUUID().toString();

    Boolean current = Main.unclaimAuto.get(uuid);
    if (current != null && current) {
      Main.unclaimAuto.put(uuid, false);
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Automatic chunk unclaim: disabled"), false);
    return 1;
  }

  private static int help(CommandContext<CommandSource> context) {
    context.getSource().sendSuccess(
    new StringTextComponent("Claim chunks with this command.\n" +
    "Press F3 + G to display chunk boundaries.\n" +
    "/chunk [info] - Get information about the chunk you are standing in.\n" +
    "/chunk [info] [auto] - Automatically get information about chunks you enter.\n" +
    "/chunk [claim] - Claims the chunk you are standing in.\n" +
    "/chunk [claim] [auto] - Disables automatic chunk claiming.\n" +
    "/chunk [claim] [auto] <size> - Automatically claims chunks you enter.\n" +
    "/chunk [unclaim] - Unclaims the chunk your are in, if you own it.\n" +
    "/chunk [unclaim] [auto] - Disables automatic chunk unclaiming.\n" +
    "/chunk [unclaim] [auto] <size> - Automatically unclaims chunks you enter.\n" +
    "/chunk [list] - Lists the chunks you own, and their coordinates.\n" +
    "/chunk [permit] <user> - Allows <user> to edit/collborate in the chunk you are in, if you claim it.\n" +
    "/chunk [unpermit] <user> - Removes the permission <user> had to collaborate in your chunk.\n" +
    "/chunk [transfer] <user> - Transfers ownership of the chunk to <user>."),
    false
    );
    return 1;
  }

  private static int claim(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    ChunkPos pos = new ChunkPos(player.xChunk, player.zChunk);

    if (ChunkClaim.checkClaimed(pos, player.level)) {
      if (ChunkClaim.checkOwner(
      player.getUUID().toString(),
      pos,
      player.level
      )) {
        throw (new SimpleCommandExceptionType(new StringTextComponent(
        pos.toString() + " already claimed!"))).create();
      } else {
        throw (new SimpleCommandExceptionType(new StringTextComponent(
        pos.toString() + " claimed by someone else!"))).create();
      }
    }

    if (!ChunkClaim.checkQuota(player.getUUID().toString())) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "You have reach the maximum amount of claims!"))).create();
    }

    if (ChunkClaim.claim(player.getUUID().toString(), pos, player.level)) {
      context.getSource().sendSuccess(new StringTextComponent(
      pos.toString() + " claimed!"), false);
    } else {
      throw FAILED_EXCEPTION.create();
    }
    return 1;
  }

  private static int unclaim(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    ChunkPos pos = new ChunkPos(player.xChunk, player.zChunk);

    if (!ChunkClaim.checkClaimed(pos, player.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      pos.toString() + " isn't claimed by anyone!"))).create();
    }

    if (!ChunkClaim.checkOwner(
    player.getUUID().toString(),
    pos,
    player.level
    )) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      pos.toString() + " claimed by someone else!"))).create();
    }

    if (ChunkClaim.unclaim(
    player.getUUID().toString(),
    pos,
    player.level
    )) {
      context.getSource().sendSuccess(new StringTextComponent(
      pos.toString() + " unclaimed!"), false);
    } else {
      throw FAILED_EXCEPTION.create();
    }
    return 1;
  }

  private static int list(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity player = context.getSource().getPlayerOrException();
    String list = ChunkClaim.list(player.getUUID().toString());
    context.getSource().sendSuccess(new StringTextComponent(list), false);
    return 1;
  }

  private static int permit(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity caller = context.getSource().getPlayerOrException();
    String callerUuid = caller.getUUID().toString();
    ChunkPos pos = new ChunkPos(caller.xChunk, caller.zChunk);

    Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(
    context,
    "user"
    );
    if (profiles.size() > 1) {
      throw TOO_MANY_EXCEPTION.create();
    }
    GameProfile gameProfile = profiles.iterator().next();
    if (gameProfile == null) {
      throw FAILED_EXCEPTION.create();
    }
    String userUuid = gameProfile.getId().toString();
    if (userUuid == null) {
      throw FAILED_EXCEPTION.create();
    }

    if (!ChunkClaim.checkClaimed(pos, caller.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "This chunk is unclaimed."))).create();
    }

    if (ChunkClaim.checkCollaborator(userUuid, pos, caller.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "This user is already permitted."))).create();
    }

    if (!ChunkClaim.checkOwner(callerUuid, pos, caller.level)) {
      throw PERMISSION_EXCEPTION.create();
    }

    if (ChunkClaim.checkOwner(userUuid, pos, caller.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "You can't permit yourself."))).create();
    }

    if (ChunkClaim.permit(callerUuid, userUuid, pos, caller.level)) {
      context.getSource().sendSuccess(new StringTextComponent(
      "Permitted " + gameProfile.getName() + "!"), false);
    } else {
      throw FAILED_EXCEPTION.create();
    }
    return 1;
  }

  private static int unpermit(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity caller = context.getSource().getPlayerOrException();
    String callerUuid = caller.getUUID().toString();
    ChunkPos pos = new ChunkPos(caller.xChunk, caller.zChunk);

    Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(
    context,
    "user"
    );
    if (profiles.size() > 1) {
      throw TOO_MANY_EXCEPTION.create();
    }
    GameProfile gameProfile = profiles.iterator().next();
    if (gameProfile == null) {
      throw FAILED_EXCEPTION.create();
    }
    String userUuid = gameProfile.getId().toString();
    if (userUuid == null) {
      throw FAILED_EXCEPTION.create();
    }

    if (!ChunkClaim.checkClaimed(pos, caller.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "This chunk is unclaimed."))).create();
    }

    if (!ChunkClaim.checkCollaborator(userUuid, pos, caller.level)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "This user is already unpermitted."))).create();
    }

    if (!ChunkClaim.checkOwner(callerUuid, pos, caller.level)) {
      throw PERMISSION_EXCEPTION.create();
    }

    if (ChunkClaim.unpermit(callerUuid, userUuid, pos, caller.level)) {
      context.getSource().sendSuccess(new StringTextComponent(
      "Unpermitted " + gameProfile.getName() + "!"), false);
    } else {
      throw FAILED_EXCEPTION.create();
    }
    return 1;
  }

  private static int transfer(CommandContext<CommandSource> context) throws CommandSyntaxException {
    Entity user = EntityArgument.getEntity(context, "user");
    return 1;
  }
}
