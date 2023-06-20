package com.gluecode.fpvdrone.server;

import com.ea.async.Async;
import com.gluecode.fpvdrone.server.claiming.ChunkClaim;
import com.gluecode.fpvdrone.server.claiming.ChunkCommand;
import com.gluecode.fpvdrone.server.racing.RaceBuild;
import com.gluecode.fpvdrone.server.racing.RaceCommand;
import com.gluecode.fpvdrone.server.racing.RaceNavigate;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.text.*;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.ea.async.Async.await;
import static org.asynchttpclient.Dsl.*;

@Mod("fpvdrone-server")
public class Main {
  public static final String MOD_ID = "fpvdrone-server";
  public static final Logger LOGGER = LogManager.getLogger(); //log4j logger.

  public static boolean isUnitTest = false;
  public static AsyncHttpClient asyncHttpClient;
  public static String version = "3.0.0"; // To be set by RaceDatabase.load
  public static MinecraftServer server;
  public static HashMap<String, Boolean> infoAuto = new HashMap<>();
  public static HashMap<String, Boolean> claimAuto = new HashMap<>();
  public static HashMap<String, Integer> claimAutoSize = new HashMap<>();
  public static HashMap<String, Boolean> unclaimAuto = new HashMap<>();
  public static HashMap<String, Integer> unclaimAutoSize = new HashMap<>();

  public static String discordAdmin = "777046656486080532";
  public static String discordRaces = "771446613822144512";
  public static String discordLogins = "772690439215906827";
  public static String discordTest = "771546499048931343";

  public Main() {
//    System.out.println("Available processors (cores): " +
//    Runtime.getRuntime().availableProcessors());
//    System.out.println("Free memory (bytes): " +
//    Runtime.getRuntime().freeMemory());
//    long maxMemory = Runtime.getRuntime().maxMemory();
//    System.out.println("Maximum memory (bytes): " +
//    (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory));
//    System.out.println("Total memory (bytes): " +
//    Runtime.getRuntime().totalMemory());

    Async.init();
    loadHttp();

//     Register ourselves for server and other game events we are interested in
    MinecraftForge.EVENT_BUS.register(this);
    MinecraftForge.EVENT_BUS.register(Main.class);

    MySQLHelper.load();
    ChunkClaim.load();
    RaceBuild.load();
    RaceNavigate.load();
  }
  
  public static void loadHttp() {
    asyncHttpClient = asyncHttpClient();
  }

  @OnlyIn(Dist.DEDICATED_SERVER)
  @SubscribeEvent
  public static void onCommandRegister(RegisterCommandsEvent event) {
    FpvCommand.register(event.getDispatcher());
    ChunkCommand.register(event.getDispatcher());
    RaceCommand.register(event.getDispatcher());
  }

  @OnlyIn(Dist.DEDICATED_SERVER)
  @SubscribeEvent
  public static void onServerStart(FMLServerStartingEvent event) {
    server = event.getServer();
  }

  @OnlyIn(Dist.DEDICATED_SERVER)
  @SubscribeEvent
  public static void onServerStop(FMLServerStoppedEvent event) {
    MySQLHelper.close();
    try {
      Main.LOGGER.info("Closing AsyncHttpClient...");
      asyncHttpClient.close();
    } catch (Exception e) {
      LOGGER.error(e);
    }
  }

  @SubscribeEvent
  public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    System.out.println("Available processors (cores): " +
    Runtime.getRuntime().availableProcessors());
    System.out.println("Free memory (bytes): " +
    Runtime.getRuntime().freeMemory());
    long maxMemory = Runtime.getRuntime().maxMemory();
    System.out.println("Maximum memory (bytes): " +
    (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory));
    System.out.println("Total memory (bytes): " +
    Runtime.getRuntime().totalMemory());

    PlayerEntity player = event.getPlayer();
    String name = getPlayerNameFromUuid(player.getUUID().toString());
    sendDiscord(":man_technologist: `" + name + "` logged in.", Main.discordLogins);
  }

//  @SubscribeEvent
//  public static void onChunkDataLoad(ChunkDataEvent.Load event) {
////    Main.LOGGER.info("loaded chunk");
//  }

  public static void shutdown(String reason) {
    ITextComponent message = new StringTextComponent(reason);
    reason = message.getString();

    //for ( Object value : SERVER.getPlayerList().getPlayerList().toArray() )
    for (Object value : server.getPlayerList().getPlayers().toArray()) {
      ServerPlayerEntity player = (ServerPlayerEntity) value;
      player.connection.disconnect(message);
      //player.connection.kickPlayerFromServer(reason);
    }

    LOGGER.debug("Shutdown initiated because: %s", reason);
    server.halt(true);
  }

  public static boolean isArmed(String userId) {
    Boolean armed = com.gluecode.fpvdrone.Main.entityArmStates.get(RaceBuild.getUUID(userId));
    return armed != null && armed;
  }

  /*
  * Todo: Make async.
  * String uuid should have dashes
  * */
  public static String getPlayerNameFromUuid(String dashedUUID) {
    if (Main.isUnitTest) return null;

    String name = null;
    GameProfile gameProfile = server.getProfileCache().get(UUID.fromString(dashedUUID));
    if (gameProfile != null) {
      name = gameProfile.getName();
    } else {
      try {
        // todo: update API:
        URL url = new URL("https://api.mojang.com/user/profiles/" + dashedUUID.replaceAll("-", "") + "/names");
        InputStream stream = url.openStream();
        try {
          StringWriter writer = new StringWriter();
          IOUtils.copy(stream, writer, StandardCharsets.UTF_8);
          String response = writer.toString();
          JSONArray nameList = (JSONArray) JSONValue.parse(response);
          JSONObject nameEntry = (JSONObject) nameList.get(nameList.size() - 1);
          name = (String) nameEntry.get("name");
        } finally {
          stream.close();
        }
      } catch (Exception e) {
        Main.LOGGER.error(e);
      }
    }

    if (name == null) {
      return "";
    } else {
      return name;
    }
  }

  public static String getPlayerUuidFromName(String name) {
    String id = null;
    GameProfile gameProfile = server.getProfileCache().get(name);
    if (gameProfile != null) {
      id = gameProfile.getId().toString();
    }

    if (id == null) {
      return "";
    } else {
      return id;
    }
  }

  public static CompletableFuture<Void> sendDiscordRaw(String message, String channelId) {
    if (channelId == null) return CompletableFuture.completedFuture(null);

    if (isUnitTest || MySQLHelper.DB_HOST.equals("localhost")) {
      channelId = discordTest;
    }

    try {
      JSONObject body = new JSONObject();
      // TODO: Imitate dotenv implementation in MySQLHelper.java.
      body.put("secret", "TODO: see .env.default");
      body.put("content", message);
      body.put("channelId", channelId);

      await(Main.asyncHttpClient
      .preparePost("TODO: see .env.default")
      .setHeader("Content-Type", "application/json")
      .setHeader("Content-Length", body.toJSONString().length())
      .setBody(body.toJSONString())
      .execute()
      .toCompletableFuture());
    } catch (Exception e) {
      Main.LOGGER.error(e);
    }
    return CompletableFuture.completedFuture(null);
  }

  public static CompletableFuture<Void> sendDiscord(String message, String channelId) {
    sendDiscordRaw(message, channelId);
    return CompletableFuture.completedFuture(null);
  }

  public static CompletableFuture<Void> sendDiscordAdmin(String message) {
    sendDiscordRaw(message, discordAdmin);
    return CompletableFuture.completedFuture(null);
  }

  public static String getDimension(@Nullable World world) {
    if (world == null) return null;
    RegistryKey<World> dimension = world.dimension();
    return dimension.location().toString();
  }

  public static void sendInfoMessage(@Nullable Entity entity, String message) {
    if (entity == null) return;
    TextComponent text = new StringTextComponent(message);
    Style style = Style.EMPTY.withColor(Color.fromLegacyFormat(TextFormatting.YELLOW));
    text.setStyle(style);
    entity.sendMessage(text, entity.getUUID());
  }

  public static void sendErrorMessage(@Nullable Entity entity, String message) {
    if (entity == null) return;
    if (message.startsWith("java.lang.Exception: ")) {
      message = message.substring(21);
    }
    TextComponent text = new StringTextComponent(message);
    Style style = Style.EMPTY.withColor(Color.fromLegacyFormat(TextFormatting.RED));
    text.setStyle(style);
    entity.sendMessage(text, entity.getUUID());
  }

  public static void sendSuccessMessage(@Nullable Entity entity, String message) {
    if (entity == null) return;
    TextComponent text = new StringTextComponent(message);
    Style style = Style.EMPTY.withColor(Color.fromLegacyFormat(TextFormatting.GREEN));
    text.setStyle(style);
    entity.sendMessage(text, entity.getUUID());
  }
}
