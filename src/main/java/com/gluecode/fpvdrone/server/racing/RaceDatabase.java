package com.gluecode.fpvdrone.server.racing;

import com.gluecode.fpvdrone.server.Main;
import com.gluecode.fpvdrone.server.MySQLHelper;
import org.asynchttpclient.Response;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.util.concurrent.CompletableFuture;

import static com.ea.async.Async.await;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class RaceDatabase {
  public static boolean loaded = false;

  public static void load() {
    if (!loaded) {
      String version = getVersionNumber().join();
      Main.version = version;
      MySQLHelper.load();
      ensureTables();
      loaded = true;
    }
  }

  public static void ensureTables() {
    /*
    To make a UTF-8 table, use: DEFAULT CHARACTER SET utf8 COLLATE utf8_unicode_ci
    * */

    // Table creation is purposely blocking.
    try {
      RaceTrack.createTable();
      RaceGate.createTable();
      RaceLap.createTable();
    } catch (Exception e) {
      Main.shutdown("Unable to load DB.");
    }
  }

  public static CompletableFuture<String> getVersionNumber() {
    Response res = await(Main.asyncHttpClient
    .prepareGet("https://minecraftfpv-assets.s3.us-east-2.amazonaws.com/version.json")
    .execute()
    .toCompletableFuture());

    String body = res.getResponseBody();
    JSONObject parsed = (JSONObject) JSONValue.parse(body);
    String version = (String) parsed.get("latest");
    if (version == null) {
      version = "1.4.3";
    }

    return completedFuture(version);
  }
}
