package com.gluecode.fpvdrone.server.racing;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;

public class RaceCommand {
  private static final SimpleCommandExceptionType FAILED_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("Unable to parse command."));
  private static final SimpleCommandExceptionType PERMISSION_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("You do not have chunk permission."));
  private static final SimpleCommandExceptionType TOO_MANY_EXCEPTION = new SimpleCommandExceptionType(
  new StringTextComponent("Only a single entity can be targeted at a time."));

  public static void register(CommandDispatcher<CommandSource> dispatcher) {
    LiteralArgumentBuilder<CommandSource> race = Commands.literal("race");

    race.executes(RaceCommand::help);
    race.then(Commands.literal("setName").then(Commands.argument(
    "name", StringArgumentType.word()
    ).executes(RaceCommand::setName)));

    dispatcher.register(race);

    // todo: toggle mode: on EnteringChunk event, tell player when they enter or leave a claim region.
  }

  private static int help(CommandContext<CommandSource> context) {
    context.getSource().sendSuccess(
    new StringTextComponent("Manage race track with this command.\n" +
    "/race setName <name> - Give a name to the track you are currently building."),
    false
    );
    return 1;
  }

  private static int setName(CommandContext<CommandSource> context) throws CommandSyntaxException {
    ServerPlayerEntity caller = context.getSource().getPlayerOrException();
    String userId = RaceBuild.getId(caller);

    if (!RaceBuild.checkBuildingMode(userId)) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      "You are not in track building mode."))).create();
    }

    String name = StringArgumentType.getString(context, "name");

    try {
      RaceBuild.setTrackName(userId, name, null);
    } catch (RaceGateException e) {
      throw (new SimpleCommandExceptionType(new StringTextComponent(
      e.endUserReason))).create();
    }

    context.getSource().sendSuccess(new StringTextComponent(
    "Track name set."), false);
    return 1;
  }
}
