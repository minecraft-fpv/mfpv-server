package com.gluecode.fpvdrone.server.racing;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

public class GetInteriorResult {
  public ArrayList<BlockPos> blocks;
  // The rowMin and rowMax represent the colIndex from the left side of the gate.
  // They are the colIndices of the solid edge blocks.
  // If a block pos is between the rowMin and rowMax, exclusive, then it is an interior block.
  public int[] rowMin;
  public int[] rowMax;
}
