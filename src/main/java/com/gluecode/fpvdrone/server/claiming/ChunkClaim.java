package com.gluecode.fpvdrone.server.claiming;

import com.gluecode.fpvdrone.server.Main;
import net.minecraft.entity.Entity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

public class ChunkClaim {
  private static final String filePath = "data/" + Main.MOD_ID + ".json";
  private static final String OWNER = "owner";
  private static final String COLLABORATORS = "collaborators";

  private static File file;
  private static boolean loaded;
  /*
  Looks like this:

  const store = {
      "[0, 0]": {
          owner: "uuid-1",
          collaborators: ["uuid-2", 'uuid-3']
      }
  }

  * */
  private static JSONObject claims = (JSONObject) JSONValue.parse("{}");
  private static HashMap<String, Integer> claimCounts = new HashMap<>();
  public static final int MAX_CLAIMS = 1000;

  public static void load() {
    try {
      Main.LOGGER.info("LOADING CHUNK CLAIM");
      Main.LOGGER.info("loaded: " + loaded);
      if (!loaded) {
        Main.LOGGER.info("Looking for: " + filePath);
        file = new File(filePath);

        if (file.exists()) {
          FileReader reader = new FileReader(file);
          claims = (JSONObject) JSONValue.parse(reader);
          reader.close();
          if (claims != null) {
            loaded = true;
          }
        }

        if (loaded) {
          Main.LOGGER.info("Successfully loaded JSON from file: " + filePath);
          int count = 0;
          for (Object pos : claims.keySet()) {
            JSONObject chunk = (JSONObject) claims.get(pos);
            String ownerUuid = (String) chunk.get(OWNER);

            int current = getClaimCount(ownerUuid);
            claimCounts.put(ownerUuid, current + 1);

            count++;
          }
          Main.LOGGER.info("There are " + count + " claims registered.");
        } else {
          claims = new JSONObject();
          loaded = true;
          save();
        }
      }
    } catch (Exception e) {
      Main.LOGGER.error(e);
      Main.shutdown("Unable to load claims. Server is unsafe.");
    }
  }

  public static void save() {
    try {
      Main.LOGGER.info("Attempting to save chunk claims...");
      Main.LOGGER.info("loaded: " + loaded);
      if (!loaded || claims == null) {
        throw new Exception("Unable to save claims. Server cannot safely run without claims.");
      }

      if (!file.exists()) {
        file.getParentFile().mkdirs();
        if (file.createNewFile()) {
          Main.LOGGER.info("Successfully empty file: " + filePath);
        }
      }

      if (!file.exists()) {
        throw new Exception("Unable to save claims. Server cannot safely run without claims.");
      }

      FileWriter writer = new FileWriter(file);
      claims.writeJSONString(writer);
      writer.close();
      Main.LOGGER.info("Saved chunk claims.");
    } catch (Exception e) {
      Main.LOGGER.error(e);
      Main.shutdown("Unable to save claims. Server is unsafe.");
    }
  }

  public static String key(ChunkPos pos, World world) {
    String dimensionKey = Main.getDimension(world);
    return "[" + dimensionKey + ", " + pos.x + ", " + pos.z + "]";
  }

  public static int getClaimCount(String ownerUuid) {
    Integer current = claimCounts.get(ownerUuid);
    if (current == null) {
      current = 0;
    }
    return current;
  }

  public static String getOwner(ChunkPos pos, World world) {
    JSONObject claim = (JSONObject) claims.get(key(pos, world));
    if (claim == null) {
      return null;
    }
    return (String) claim.get(OWNER);
  }

  public static ArrayList<String> getCollaborators(ChunkPos pos, World world) {
    JSONObject claim = (JSONObject) claims.get(key(pos, world));
    if (claim == null) {
      return null;
    }
    return (ArrayList<String>) claim.get(COLLABORATORS);
  }

  public static boolean checkQuota(String ownerUuid) {
    return getClaimCount(ownerUuid) < ChunkClaim.MAX_CLAIMS;
  }

  public static boolean checkClaimed(ChunkPos pos, World world) {
    JSONObject claim = (JSONObject) claims.get(key(pos, world));
    return claim != null;
  }

  public static boolean checkOwner(String entity, ChunkPos pos, World world) {
    String owner = getOwner(pos, world);
    if (owner == null) {
      // unclaimed chunk
      return false;
    }
    return entity.equalsIgnoreCase(owner);
  }

  /*
  * Entity caller is used to get the current dimension.
  * */
  public static boolean checkCollaborator(String entity, ChunkPos pos, World world) {
    ArrayList<String> collaborators = getCollaborators(pos, world);
    if (collaborators == null) {
      return false;
    }
    return collaborators.contains(entity);
  }

  /*
   * Entity caller is used to get the current dimension.
   * */
  public static boolean checkPermission(String entity, ChunkPos pos, World world) {
    boolean isClaimed = checkClaimed(pos, world);
    if (!isClaimed) return true;
    boolean isOwner = checkOwner(entity, pos, world);
    if (isOwner) return true;
    return checkCollaborator(entity, pos, world);
  }

  // returns true := success
  public static boolean claim(String entity, ChunkPos pos, World world) {
    if (checkClaimed(pos, world)) {
      return false;
    }

    int count = getClaimCount(entity);
    if (count >= MAX_CLAIMS) {
      return false;
    }

    JSONObject newClaim = new JSONObject();
    newClaim.put(OWNER, entity);
    newClaim.put(COLLABORATORS, new JSONArray());
    claims.put(key(pos, world), newClaim);

    claimCounts.put(entity, count + 1);

    save();
    return true;
  }

  public static boolean unclaim(String entity, ChunkPos pos, World world) {
    if (!checkOwner(entity, pos, world)) {
      return false;
    }

    claims.remove(key(pos, world));

    int current = getClaimCount(entity);
    claimCounts.put(entity, current - 1);

    save();
    return true;
  }

  public static String info(String entity, ChunkPos pos, World world) {
    boolean isClaimed = checkClaimed(pos, world);
    if (!isClaimed) {
      return pos.toString() + " is unclaimed.";
    }

    String ownerUuid = getOwner(pos, world);
    String ownerName = Main.getPlayerNameFromUuid(ownerUuid);

    boolean isOwner = checkOwner(entity, pos, world);
    if (isOwner) {
      StringBuilder builder = new StringBuilder(pos.toString() + " members: " + ownerName + " + [");
      ArrayList<String> collabs = getCollaborators(pos, world);
      if (collabs != null) {
        for (String uuid : collabs) {
          builder.append(Main.getPlayerNameFromUuid(uuid)).append(", ");
        }
      }
      String response = builder.toString();
      Main.LOGGER.info(response);
      if (response.endsWith(", ")) {
        response = response.substring(0, response.length() - 2);
      }
      response += "]";
      return response;
    }

    boolean isCollaborator = ChunkClaim.checkCollaborator(entity, pos, world);
    if (isCollaborator) {
      return pos.toString() + " owner: " + ownerName + " -- You may edit this chunk.";
    } else {
      return pos.toString() + " owner: " + ownerName;
    }
  }

  public static String list(String entityUuid) {
    ArrayList<String> ownerChunks = new ArrayList<>();
    ArrayList<String> collaboratorChunks = new ArrayList<>();
    for (Object key : claims.keySet()) {
      JSONObject claimObject = (JSONObject) claims.get(key);
      if (claimObject == null) continue;

      String ownerUuid = (String) claimObject.get(OWNER);
      if (ownerUuid != null) {
        boolean uuidMatch = ownerUuid.equalsIgnoreCase(entityUuid);
        if (uuidMatch) {
          ownerChunks.add((String) key);
        }
      }

      JSONArray collabs = (JSONArray) claimObject.get(COLLABORATORS);
      if (collabs != null) {
        boolean uuidMatch = collabs.contains(entityUuid);
        if (uuidMatch) {
          collaboratorChunks.add((String) key);
        }
      }
    }

    StringBuilder builder = new StringBuilder();

    String ownerString;
    for (String pos : ownerChunks) {
      builder.append(pos).append("\n");
    }
    if (builder.length() == 0) {
      ownerString = "You have no claims.";
    } else {
      builder.insert(0, "Your claims:\n");
      ownerString = builder.toString();
    }

    builder = new StringBuilder();

    String collabString;
    for (String pos : collaboratorChunks) {
      JSONObject claimObject = (JSONObject) claims.get(pos);
      builder.append(Main.getPlayerNameFromUuid((String) claimObject.get(OWNER))).append(": ").append(pos).append("\n");
    }
    if (builder.length() == 0) {
      collabString = "No one has permitted you to edit their chunks.";
    } else {
      builder.insert(0, "Your are permitted:\n");
      collabString = builder.toString();
    }

    int count = getClaimCount(entityUuid);
    int remaining = MAX_CLAIMS - count;
    String quota = remaining + " claim quota available.";

    return ownerString + "\n" + collabString + "\n" + quota;
  }

  public static boolean permit(String owner, String entity, ChunkPos pos, World world) {
    if (checkCollaborator(entity, pos, world)) {
      // already added
      return false;
    }

    if (checkOwner(owner, pos, world)) {
      // only owner can do this.

      if (checkOwner(entity, pos, world)) {
        // can't permit owner.
        return false;
      }

      JSONObject existingClaim = (JSONObject) claims.get(key(pos, world));
      JSONArray collabs = (JSONArray) existingClaim.get(COLLABORATORS);
      collabs.add(entity);
      save();
      return true;
    } else {
      return false;
    }
  }

  public static boolean unpermit(String owner, String entity, ChunkPos pos, World world) {
    if (!checkCollaborator(entity, pos, world)) {
      // not added
      return false;
    }

    if (checkOwner(owner, pos, world)) {
      // only owner can do this.
      String entityUuid = entity;
      JSONObject existingClaim = (JSONObject) claims.get(key(pos, world));
      JSONArray collabs = (JSONArray) existingClaim.get(COLLABORATORS);
      int index = -1;
      for (int i = 0; i < collabs.size(); i++) {
        String uuid = (String) collabs.get(i);
        if (uuid.equalsIgnoreCase(entityUuid)) {
          index = i;
          break;
        }
      }
      collabs.remove(index);
      save();
      return true;
    } else {
      return false;
    }
  }

  public static void transfer(Entity entity, ChunkPos pos) {
    save();
  }
}
