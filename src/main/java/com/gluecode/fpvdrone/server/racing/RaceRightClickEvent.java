package com.gluecode.fpvdrone.server.racing;

import net.minecraft.util.math.BlockPos;

public class RaceRightClickEvent {
  public static int hashCode(String userId, String dimension, BlockPos pos) {
    return userId.hashCode() + dimension.hashCode() + pos.hashCode();
  }
}
