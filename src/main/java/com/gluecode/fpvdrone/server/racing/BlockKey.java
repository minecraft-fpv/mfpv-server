package com.gluecode.fpvdrone.server.racing;

import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

public class BlockKey {
  public String dimension;
  public BlockPos pos;

  @Nullable
  public Direction face;

  public BlockKey(String dimension, BlockPos pos) {
    this.dimension = dimension;
    this.pos = pos;
  }

  public int hashCode() {
    return this.dimension.hashCode() + this.pos.hashCode();
  }

  public boolean equals(Object o) {
    if (!(o instanceof BlockKey)) {
      return false;
    }
    if (this == o) {
      return true;
    }
    return this.dimension.equals(((BlockKey) o).dimension) &&
    this.pos.equals(((BlockKey) o).pos);
  }

  public String toString() {
    return dimension + ", " + pos;
  }
}
