package com.gluecode.fpvdrone.server.racing;


import com.gluecode.fpvdrone.server.Main;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3i;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.function.BiPredicate;

public class RaceMath {
  public static final int MAX_PATH_LENGTH = 64;
  public static final float degs = 180f / FastMath.PI;

  public static int countFixedAxes2(BlockPos a, BlockPos b) {
    // Diagonal lines and planes are not considered.
    // If only 1 axis is fixed, then the blocks are planar.
    // If more than 1 axes are fixed, then the blocks are linear.

    int fixedAxes = 0;

    int ax = a.getX();
    int bx = b.getX();
    if (ax == bx) {
      // x is fixed
      fixedAxes++;
    }

    int az = a.getZ();
    int bz = b.getZ();
    if (az == bz) {
      // z is fixed
      fixedAxes++;
    }

    int ay = a.getY();
    int by = b.getY();
    if (ay == by) {
      // y is fixed
      fixedAxes++;
    }

    return fixedAxes;
  }

  public static int countFixedAxes(BlockPos a, BlockPos b, BlockPos c) {
    // Diagonal lines and planes are not considered.
    // If only 1 axis is fixed, then the blocks are planar.
    // If more than 1 axes are fixed, then the blocks are linear.

    int fixedAxes = 0;

    int ax = a.getX();
    int bx = b.getX();
    int cx = c.getX();
    if (ax == bx && ax == cx) {
      // x is fixed
      fixedAxes++;
    }

    int az = a.getZ();
    int bz = b.getZ();
    int cz = c.getZ();
    if (az == bz && az == cz) {
      // z is fixed
      fixedAxes++;
    }

    int ay = a.getY();
    int by = b.getY();
    int cy = c.getY();
    if (ay == by && ay == cy) {
      // y is fixed
      fixedAxes++;
    }

    return fixedAxes;
  }

  /*
  fixedDimension == 0 represent x axis does not change
  fixedDimension == 1 represent y axis does not change
  fixedDimension == 2 represent z axis does not change
  * */
  public static int getPlanarFixedDim(BlockPos a, BlockPos b, BlockPos c) throws RaceGateException {
    // Diagonal lines and planes are not considered.
    // If only 1 axis is fixed, then the blocks are planar.
    // If 2 axes are fixed, then the blocks are linear.

    int nFixedDims = countFixedAxes(a, b, c);
    if (nFixedDims != 1) {
      throw new RaceGateException("Exactly 1 dimension should be fixed in order to be planar non-linear.");
    }

    int ax = a.getX();
    int bx = b.getX();
    int cx = c.getX();
    if (ax == bx && ax == cx) {
      // x is fixed
      return 0;
    }

    int az = a.getZ();
    int bz = b.getZ();
    int cz = c.getZ();
    if (az == bz && az == cz) {
      // z is fixed
      return 2;
    }

    int ay = a.getY();
    int by = b.getY();
    int cy = c.getY();
    if (ay == by && ay == cy) {
      // y is fixed
      return 1;
    }

    throw new RaceGateException("Unable to find fixed dim.");
  }

  /*
  Returns:
    * result[0] := right
    * result[1] := up
    * result[2] := normal = right x up
  * */
  public static Vector3f[] getPlanarDirections(int fixedDimension) {
    Vector3f right = Vector3f.ZERO;
    Vector3f up = Vector3f.ZERO;
    if (fixedDimension == 0) {
      right = new Vector3f(0, 0, 1);
      up = new Vector3f(0, 1, 0);
    } else if (fixedDimension == 1) {
      right = new Vector3f(1, 0, 0);
      up = new Vector3f(0, 0, 1);
    } else if (fixedDimension == 2) {
      right = new Vector3f(1, 0, 0);
      up = new Vector3f(0, 1, 0);
    }
    Vector3f normal = right.cross(up);
    Vector3f[] result = new Vector3f[3];
    result[0] = right;
    result[1] = up;
    result[2] = normal;
    return result;
  }

  /*

  Requirements:
    * The gate must be axis aligned.
    * The gate must be convex and closed.
    * Eligible edge blocks must be touching air on the interior side of the gate. (Corners touching does not count)

  Returns:
    * min AABB
    * max AABB
    * list of interior air blocks which are inside the gate but
      not inside min AABB
  * */
  public static RaceGate loadGate(String dimension, BlockPos a, Direction face, BlockPos b, BiPredicate<String, BlockPos> checkSolid, @Nullable RaceGate local) throws RaceGateException {
    /*
    Overview of the algorithm:
      1. Reduce the dimensionality of the problem to 2D since the gate is AA.
      2. Find the air block touching face to face with block A on the interior of the gate.
      3. Starting with block A and it's air block, use stepGateBlock and rotateAirBlock to traverse the gate's edge.
      4. If a solid block is visited twice, throw error.
      5. If iteration limit is reached, throw error.
      6. Look through the list of edge blocks to see if it contains A, B, and C.
      7. Compute AABB. This will be used later for improving gate-passing detection.
      8. Create a list of interior blocks, along with the min and max positions for each row.
      9. Check that every block defined by min and max is air.
      10. Return the AABB and the rowMin and rowMax arrays. These will be used for gate-passing detection.
    * */

    // Determine a BlockPos for the air block touching the face of A:
    BlockPos air = a.offset(face.getNormal());

    // Verify solidity
    if (!checkSolid.test(dimension, a) || !checkSolid.test(dimension, b) || checkSolid.test(dimension, air)) {
      throw new RaceGateException("Starting blocks do not have the correct solidity.");
    }

    int nFixedDims = countFixedAxes(a, b, air);
    if (nFixedDims != 1) {
      throw new RaceGateException("Unable to determine axis-aligned dimension.");
    }
    int fixedDim = getPlanarFixedDim(a, b, air);

    // Verify touching face to face:
    int distB = a.distManhattan(b);
    boolean bTouching = distB == 1 || (distB == 2 && countFixedAxes2(a, b) == 1);
    boolean airTouching = a.distManhattan(air) == 1;
    if (!bTouching || !airTouching) {
      throw new RaceGateException("Starting blocks are not touching correctly.");
    }

    boolean bVisited = false;
    BlockPos currentSolid = a;
    BlockPos currentAir = rotateAirBlock(fixedDim, dimension, currentSolid, air, checkSolid); // rotate air before doing anything in order to be in a handled casee.
    ArrayList<BlockPos> path = new ArrayList<>(MAX_PATH_LENGTH);
    path.add(a);
    for (int length = 1; length < MAX_PATH_LENGTH + 1; length++) {
      BlockPos[] results = stepGateBlock(fixedDim, dimension, currentSolid, currentAir, checkSolid);

      if (path.size() >= 2 && results[0].equals(path.get(path.size() - 2))) {
        // Dead end reached. Gate path is not closed.
        throw new RaceGateException("Dead end. Gate not closed.");
      }

      currentSolid = results[0];
      currentAir = results[1];
      currentAir = rotateAirBlock(fixedDim, dimension, currentSolid, currentAir, checkSolid);

      if (currentSolid.equals(b)) {
        bVisited = true;
      }

      if (currentSolid.equals(a)) {
        // Break out when beginning is reached again.
        break;
      }

      path.add(currentSolid); // This must be called in order to avoid infinite loop.
    }
    if (path.size() > MAX_PATH_LENGTH) {
      throw new RaceGateException("Found path is greater than the allowed maximum.", "Gate is too big.");
    }
    if (path.size() == MAX_PATH_LENGTH) {
      if (!currentSolid.equals(a)) {
        throw new RaceGateException("Max iteration reached without wrapping back to starting point.", "Gate is too big.");
      }
    }
    if (!bVisited) {
      throw new RaceGateException("B was not visited.");
    }

    BlockPos[] AABBResult = getAABB(path);
    BlockPos origin = AABBResult[0];
    BlockPos farthest = AABBResult[1];
    GetInteriorResult interiorResult = getInteriorBlocks(fixedDim, AABBResult, path);
    ArrayList<BlockPos> interior = interiorResult.blocks;
    int[] rowMin = interiorResult.rowMin;
    int[] rowMax = interiorResult.rowMax;

    // Loop through interior and make sure they are all non-solid:
    boolean foundAir = false;
    for (BlockPos interiorBlock : interior) {
      boolean isSolid = checkSolid.test(dimension, interiorBlock);
      if (isSolid) {
        throw new RaceGateException("Found a solid interior block.", "Gate interior should not have solid blocks.");
      }
      if (air.equals(interiorBlock)) {
        foundAir = true;
      }
    }

    // The initial air block should have been found too.
    if (!foundAir) {
      throw new RaceGateException("Air was not visited.", "You must click on the gate's interior face.");
    }

    Vector3f[] planarDirections = getPlanarDirections(fixedDim);
    Vector3f right = planarDirections[0];
    Vector3f up = planarDirections[1];
    Vector3f normal = planarDirections[2];

    RaceGate result;
    if (local == null) {
      result = new RaceGate(dimension, a, face, b);
    } else {
      result = local;
    }
    result.setBoundaries(origin, farthest, rowMin, rowMax, right, up, normal, path);
    return result;
  }

  /*
  Returns:
    * result[0] = origin (minimum coordinates)
    * result[1] = farthest (maximum coordinates)
  * */
  public static BlockPos[] getAABB(ArrayList<BlockPos> path) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;

    for (BlockPos edgeBlock: path) {
      int testX = edgeBlock.getX();
      int testY = edgeBlock.getY();
      int testZ = edgeBlock.getZ();
      if (testX < minX) {
        minX = testX;
      }
      if (testY < minY) {
        minY = testY;
      }
      if (testZ < minZ) {
        minZ = testZ;
      }
      if (testX > maxX) {
        maxX = testX;
      }
      if (testY > maxY) {
        maxY = testY;
      }
      if (testZ > maxZ) {
        maxZ = testZ;
      }
    }

    BlockPos[] result = new BlockPos[2];
    result[0] = new BlockPos(minX, minY, minZ);
    result[1] = new BlockPos(maxX, maxY, maxZ);
    return result;
  }

  public static GetInteriorResult getInteriorBlocks(int fixedDimension, BlockPos[] maxAABB, ArrayList<BlockPos> path) throws RaceGateException {
    Vector3f[] planarDirections = getPlanarDirections(fixedDimension);
    Vector3f right = planarDirections[0];
    Vector3f up = planarDirections[1];

    Vector3f origin = new Vector3f(maxAABB[0].getX(), maxAABB[0].getY(), maxAABB[0].getZ());
    Vector3f maxCorner = new Vector3f(maxAABB[1].getX(), maxAABB[1].getY(), maxAABB[1].getZ());
    Vector3f span = maxCorner.subtract(origin);
    Vector3f spanProjUp = span.project(up);
    Vector3f spanProjRight = span.project(right);
    int maxRowIndex = Math.round(spanProjUp.length());
    int nRows = maxRowIndex + 1;
    int maxColIndex = Math.round(spanProjRight.length());
    int nCols = maxColIndex + 1;

    // Counting from the bottom of the gate, the rowIndex is the current row
    // of the edgeBlock.
    // colIndex is similar, being the count of blocks from the left edge of the gate.

    ArrayList<HashMap<Integer, BlockPos>> rows = new ArrayList<>();
    ArrayList<HashMap<Integer, BlockPos>> cols = new ArrayList<>();

    for (int i = 0; i < nRows; i++) {
      rows.add(new HashMap<>());
    }
    for (int i = 0; i < nCols; i++) {
      cols.add(new HashMap<>());
    }

    // maps a rowIndex to a colIndex.
    int[] rowMin = new int[nRows];
    int[] rowMax = new int[nRows];
    for (int i = 0; i < nRows; i++) {
      rowMin[i] = Integer.MAX_VALUE;
      rowMax[i] = Integer.MIN_VALUE;
    }

    // maps a colIndex to a rowIndex.
    int[] colMin = new int[nCols];
    int[] colMax = new int[nCols];
    for (int i = 0; i < nCols; i++) {
      colMin[i] = Integer.MAX_VALUE;
      colMax[i] = Integer.MIN_VALUE;
    }

    // Loop through the edges once to find the max and min column of each row.
    // And the min and max row of each column.
    Vector3f edgePos = new Vector3f(0, 0, 0);
    for (BlockPos edgeBlock : path) {
      edgePos.x = edgeBlock.getX();
      edgePos.y = edgeBlock.getY();
      edgePos.z = edgeBlock.getZ();

      Vector3f localEdgePos = edgePos.subtract(origin);
      Vector3f projUp = localEdgePos.project(up);
      Vector3f projRight = localEdgePos.project(right);
      int rowIndex = Math.round(projUp.length());
      int colIndex = Math.round(projRight.length());

      if (colIndex < rowMin[rowIndex]) {
        rowMin[rowIndex] = colIndex;
      }
      if (colIndex > rowMax[rowIndex]) {
        rowMax[rowIndex] = colIndex;
      }

      if (rowIndex < colMin[colIndex]) {
        colMin[colIndex] = rowIndex;
      }
      if (rowIndex > colMax[colIndex]) {
        colMax[colIndex] = rowIndex;
      }

      rows.get(rowIndex).put(colIndex, edgeBlock);
      cols.get(colIndex).put(rowIndex, edgeBlock);
    }

    // The returned rowMin and rowMax are actually the outter-most mins and maxes,
    // which means there may be solid interior blocks.
    int[] outerRowMin = new int[nRows];
    int[] outerRowMax = new int[nRows];
    for (int i = 0; i < nRows; i++) {
      outerRowMin[i] = rowMin[i];
      outerRowMax[i] = rowMax[i];
    }

    int count;
    BlockPos next;
    
    // Now remove blocks which are contiguous along a row:
    for (int rowIndex = 0; rowIndex < nRows; rowIndex++) {
      // Shrink rowMin:
      count = 0;
      do {
        int colIndex = rowMin[rowIndex];
        next = rows.get(rowIndex).get(colIndex + 1);

        rows.get(rowIndex).remove(colIndex); // Check off the list (every block must be visited)
        
        if (next != null) {
          // There is a neighboring solid block on this row.
          rowMin[rowIndex] = colIndex + 1; // Shrink rowMin
        }
        count++;
      } while (next != null && count < MAX_PATH_LENGTH);

      // Shrink rowMax:
      count = 0;
      do {
        int colIndex = rowMax[rowIndex];
        next = rows.get(rowIndex).get(colIndex - 1);

        rows.get(rowIndex).remove(colIndex); // Check off the list

        if (next != null) {
          // There is a neighboring solid block on this row.
          rowMax[rowIndex] = colIndex - 1; // Shrink rowMax
        }
        count++;
      } while (next != null && count < MAX_PATH_LENGTH);
    }

    // Now remove blocks which are contiguous along a column (endcaps not checked):
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      // Shrink colMin:
      count = 0;
      do {
        int rowIndex = colMin[colIndex];
        next = cols.get(colIndex).get(rowIndex + 1);

        cols.get(colIndex).remove(rowIndex); // Check off the list

        if (next != null) {
          // There is a neighboring solid block on this col.
          colMin[colIndex] = rowIndex + 1; // Shrink colMin
        }
        count++;
      } while (next != null && count < MAX_PATH_LENGTH);

      // Shrink colMax:
      count = 0;
      do {
        int rowIndex = colMax[colIndex];
        next = cols.get(colIndex).get(rowIndex - 1);

        cols.get(colIndex).remove(rowIndex); // Check off the list

        if (next != null) {
          // There is a neighboring solid block on this col.
          colMax[colIndex] = rowIndex - 1; // Shrink colMax
        }
        count++;
      } while (next != null && count < MAX_PATH_LENGTH);
    }

    // Verify that all the blocks have been checked off the list (aka there are no non-contiguous edge blocks, aka concave):
    for (int rowIndex = 0; rowIndex < nRows; rowIndex++) {
      if (rows.get(rowIndex).size() != 0) {
        throw new RaceGateException("Gate is not convex.", "Gate is not convex.");
      }
    }
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      if (cols.get(colIndex).size() != 0) {
        throw new RaceGateException("Gate is not convex.", "Gate is not convex.");
      }
    }

    // Loop through each row's columns, starting and ending at the rowMin and rowMax, exclusive.
    // It must be exclusive because the endpoints should be solid.
    ArrayList<BlockPos> blocks = new ArrayList<>();
    for (int row = 1; row < nRows - 1; row++) {
      int currentRowMin = rowMin[row];
      int currentRowMax = rowMax[row];
      for (int col = currentRowMin + 1; col <= currentRowMax - 1; col++) {
        Vector3f pos = origin.add(right.mult(col)).add(up.mult(row));
        blocks.add(new BlockPos(Math.round(pos.x), Math.round(pos.y), Math.round(pos.z)));
      }
    }
    GetInteriorResult result = new GetInteriorResult();
    result.blocks = blocks;
    result.rowMin = outerRowMin;
    result.rowMax = outerRowMax;
    return result;
  }

  /*
  When looking down the normal, this will get the next solid block in the CCW direction.

  There are only 2 cases allowed:

  1:
    aa
    ss

  2:
    as
    s

  Notice that this case is the same as case #1 but rotated 90 degrees:

  3:
    aa
    sa
     s

  The rotateAirBlock function will turn case #3 into case #1.
  * */
  private static BlockPos[] stepGateBlock(int fixedDimension, String worldDimension, BlockPos currentSolid, BlockPos currentAir, BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
    Vector3f[] planarDirections = getPlanarDirections(fixedDimension);
    Vector3f normal = planarDirections[2];

    Vector3f solidPos = new Vector3f(currentSolid.getX(), currentSolid.getY(), currentSolid.getZ());

    // Figure out the CCW direction.
    Vector3f solidToAir = new Vector3f(currentAir.getX() - currentSolid.getX(), currentAir.getY() - currentSolid.getY(), currentAir.getZ() - currentSolid.getZ());
    Vector3f nextDir = solidToAir.cross(normal).normalizeLocal();

    // Check if we are in case 1 (straight) or case 2 (diagonal):
    BlockPos diagonalPos = convertToBlockPos(solidPos.add(nextDir).add(solidToAir));
    boolean isDiagonalSolid = checkSolid.test(worldDimension, diagonalPos);
    BlockPos straightPos = convertToBlockPos(solidPos.add(nextDir));
    boolean isStraightSolid = checkSolid.test(worldDimension, straightPos);
    if (isDiagonalSolid) {
      BlockPos[] result = new BlockPos[2];
      result[0] = diagonalPos;
      result[1] = currentAir;
      return result;
    } else if (isStraightSolid) {
      // In this case, the currentAir also changes by sliding over 1 block.
      BlockPos[] result = new BlockPos[2];
      result[0] = straightPos;
      result[1] = diagonalPos;
      return result;
    } else {
      RaceGateException e = new RaceGateException("Unable to determine if we are in case 1 or 2.");
      throw e;
    }
  }

  /*
  This will rotate the air block around the solid block in the CW direction, from face to face,
  until it cannot be rotated anymore because there is a solid block in the way.

  Note that stepGateBlock actually changes the air block in the straight case, so that
  the only starting state rotateAirBlock has to worry about is this case:

    a
    s

  A solid block in the way could be the straight case or diagonal case:

  1:
    aa
    ss

  2:
    as
    sa

  * */
  private static BlockPos rotateAirBlock(int fixedDimension, String worldDimension, BlockPos currentSolid, BlockPos currentAir, BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
    Vector3f[] planarDirections = getPlanarDirections(fixedDimension);
    Vector3f normal = planarDirections[2];

    Vector3f solidPos = new Vector3f(currentSolid.getX(), currentSolid.getY(), currentSolid.getZ());
    BlockPos testAir = new BlockPos(currentAir.getX(), currentAir.getY(), currentAir.getZ());

    // Only 3 rotations can be done before going back to the beginning.
    for (int i = 0; i < 3; i++) {
      Vector3f solidToAir = new Vector3f(testAir.getX() - currentSolid.getX(), testAir.getY() - currentSolid.getY(), testAir.getZ() - currentSolid.getZ());
      Vector3f nextDir = solidToAir.cross(normal).normalizeLocal();

      BlockPos diagonalPos = convertToBlockPos(solidPos.add(nextDir).add(solidToAir));
      boolean isDiagonalSolid = checkSolid.test(worldDimension, diagonalPos);
      BlockPos straightPos = convertToBlockPos(solidPos.add(nextDir));
      boolean isStraightSolid = checkSolid.test(worldDimension, straightPos);

      if (isDiagonalSolid || isStraightSolid) {
        // We can't rotate anymore so return the current testAir.
        return testAir;
      } else {
        // We can rotate to the nextPos
        testAir = straightPos;
      }
    }

    throw new RaceGateException("Unable to find neighboring solid block.", "A single block cannot be a gate.");
  }

  /*
  Returns:
    * result[0] is the block to the left (looking towards center)
    * result[1] is the block to the right (looking towards center)


  Tests will pass if:
    * The given test block is on the edge of a convex path.
    * The given center point is in side the convex path.
    * There are 2 other edge blocks neighboring the test block. (corners count)
    * Test block is touching an air block (corners do NOT count).
    * The number of viable edge blocks in the gate is 1002 or less.

  fixedDimension == 0 represent x axis does not change
  fixedDimension == 1 represent y axis does not change
  fixedDimension == 2 represent z axis does not change

  Proof of correctness:
  Draw a line from A to the center of the triangle ABC.
    Considering the 8 blocks surrounding A, the line from A to center,
    determine the angle between the two lines:
      * A to triangle center.
      * A to block center.
    The angles should be defined from (-180, 180) exclusive.

    Since the gate is convex, A is on an edge, and ABC is a triangle,
    it should be possible to do the following:
      * Find the solid block with positive angle which has the minimum magnitude among all positive angle blocks.
      * Find a solid block with negative angle which has the minimum magnitude among all negative angle blocks.
    If found, these 2 solid blocks are edge blocks.

    The reason this works is because the line from A to triangle center:
      * always points toward the gate interior since the gate is convex.
      * does not lie along the edge of the gate because the triangle point are not colinear.
    Therefore if you are standing at A and looking toward the center of the triangle,
    if you scan to your left, you should eventually see a solid block which is an edge block,
    and if you scan to your right, you should eventually see a solid block which is an edge block.
  * */
  private static BlockPos[] getNeighboringEdgeBlocks(Vector3f triangleCenter, int fixedDimension, BlockPos testBlock, String worldDimension, BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
    Vector3f[] planarDirections = getPlanarDirections(fixedDimension);
    Vector3f right = planarDirections[0];
    Vector3f up = planarDirections[1];
    Vector3f normal = planarDirections[2];
    Vector3f left = right.mult(-1);
    Vector3f down = up.mult(-1);

    Vector3i righti = new Vector3i(Math.round(right.x),
    Math.round(right.y),
    Math.round(right.z)
    );
    Vector3i upi = new Vector3i(Math.round(up.x),
    Math.round(up.y),
    Math.round(up.z)
    );

    Vector3f testBlockFloat = new Vector3f(testBlock.getX(),
    testBlock.getY(),
    testBlock.getZ()
    );

    Vector3f testToCenter = triangleCenter.subtract(testBlockFloat).normalize();

    Vector3f[] surroundingBlockDirections = new Vector3f[8];
    surroundingBlockDirections[0] = right;
    surroundingBlockDirections[1] = right.add(up).normalize();
    surroundingBlockDirections[2] = up;
    surroundingBlockDirections[3] = left.add(up).normalize();
    surroundingBlockDirections[4] = left;
    surroundingBlockDirections[5] = left.add(down).normalize();
    surroundingBlockDirections[6] = down;
    surroundingBlockDirections[7] = right.add(down).normalize();

    /*
    Assign each surrounding block an angle relative to the vector from the
    test block to the triangle center.
    Surrounding blocks which are rotationally to the left of the center are given a positive angle.
    Blocks which are to the right of the center are given a negative angle.
    * */
    ArrayList<IndexedAbsFloat> angles = new ArrayList<>(8);
    for (int i = 0; i < surroundingBlockDirections.length; i++) {
      float angle = angleBetweenWithSign(testToCenter, surroundingBlockDirections[i], normal);
      IndexedAbsFloat indexedAngle = new IndexedAbsFloat(angle, i);
      angles.add(indexedAngle);
    }

    // Filter out non-solid blocks:
    ArrayList<IndexedAbsFloat> solidAngles = new ArrayList<>(8);
    for (IndexedAbsFloat angle : angles) {
      BlockPos blockPos = getBlockPosFromDirectionalIndex(testBlock,
      angle.index,
      righti,
      upi
      );
      boolean isSolid = checkSolid.test(worldDimension, blockPos);
      if (isSolid) {
        solidAngles.add(angle);
      }
    }

    if (solidAngles.size() <= 1) {
      // need at least 2 solid blocks
      throw new RaceGateException("At least two solid blocks were not found.", "Gate is not closed.");
    }

    // The two smallest angle solid blocks correspond to the edge blocks neighboring the test block.
    // This is because they are the two blocks rotationally closest to the center of the triangle.
    // But what if the the line to center coincides with a surrounding block direction?
    // One of the angles would be 0, and there would be 2 other blocks which have
    // the same non-zero minimum angle.
    // It is impossible for the line to center to coincide with edge block directions
    // because the triangle center is determined by 3 non-colinear blocks.
    // Therefore, if the line to center coincides with a direction, that direction
    // does not correspond to an edge block, and it actually should be an interior (non-solid)
    // block.
    // Since it is non-solid, it should not be in the solidAngles array.
    // If there is a 0 angle block in the solidAngles array, then throw an RaceGateException.

    // Note on usage of 0.001f:
    // The gate is limited to 56 edge blocks which is a 16 x 16 gate with no corners.
    // So that means the largest aspect ratio is 1:27,
    // which is a gate 1 block tall and 27 blocks wide, measured from the interior.
    // This means the smallest non-zero is 2.121 degrees.
    // We will treat angles less that .001 rad as equal to 0.
    // This means angles less than 0.0572 degs are treated as 0.
    // This actually sets the upper limit for the number of edge blocks in the gate at 1002.

    Collections.sort(solidAngles);
   if (FastMath.abs(solidAngles.get(0).value) < 0.001f) {
      throw new RaceGateException("Found a solid block with 0 angle.", "Gate interior has solid blocks.");
    }

    IndexedAbsFloat edgeAngle1 = solidAngles.get(0);
    IndexedAbsFloat edgeAngle2 = null;

    // There should be one edge block to the left and one to the right, rotationally,
    // relative the to the triangle center.
    // That means edge1 and edge2 should have opposite angles.
    for (int i = 1; i < solidAngles.size(); i++) {
      if (edgeAngle1.value > 0) {
        // edgeAngle2 should be negative
        if (solidAngles.get(i).value < 0) {
          edgeAngle2 = solidAngles.get(i);
          break;
        }
      } else if (edgeAngle1.value < 0) {
        // edgeAngle2 should be positive
        if (solidAngles.get(i).value > 0) {
          edgeAngle2 = solidAngles.get(i);
          break;
        }
      }
    }

    if (edgeAngle2 == null) {
      throw new RaceGateException("Unable to find edgeAngle2.", "Gate is not closed or convex.");
    }

    // Since the gate is concave, the two edge blocks should not have a sum of angles
    // relative to the triangle center equal to or greater that 180.

    float sum = FastMath.abs(edgeAngle1.value) + FastMath.abs(edgeAngle2.value);
    if (sum - FastMath.PI > 0.001f) {
      throw new RaceGateException("Edge blocks greater than 180", "Gate is not convex.");
    }

    BlockPos edge1 = getBlockPosFromDirectionalIndex(testBlock,
    edgeAngle1.index,
    righti,
    upi
    );
    BlockPos edge2 = getBlockPosFromDirectionalIndex(testBlock,
    edgeAngle2.index,
    righti,
    upi
    );

    // verify that the test block is touching air on an edge, not a corner.
    boolean isTouchingAir = false;
    for (int i = 0; i < surroundingBlockDirections.length; i++) {
      // diagonal directions not allowed
      if (i % 2 == 1) {
        continue;
      }

      // direction must be between edge1 and edge2
      Vector3f testToAir = surroundingBlockDirections[i];
      float airAngle = angleBetweenWithSign(testToCenter, testToAir, normal);
      float maxAngle = Math.max(edgeAngle1.value, edgeAngle2.value);
      float minAngle = Math.min(edgeAngle1.value, edgeAngle2.value);
      if (!(minAngle < airAngle && airAngle < maxAngle)) {
        // airAngle is not between the two edge blocks.
        continue;
      }

      BlockPos blockPos = getBlockPosFromDirectionalIndex(testBlock,
      i,
      righti,
      upi
      );
      boolean isSolid = checkSolid.test(worldDimension, blockPos);
      if (!isSolid) {
        isTouchingAir = true;
        break;
      }
    }
    if (!isTouchingAir) {
      throw new RaceGateException("Gate blocks must be touching air (corners do not count). " + testBlock, "Gate blocks must be touching air (corners do not count).");
    }

    BlockPos[] result = new BlockPos[2];
    if (edgeAngle1.value > 0) {
      result[0] = edge1;
      result[1] = edge2;
    } else {
      result[0] = edge2;
      result[1] = edge1;
    }
    return result;
  }

  private static BlockPos getBlockPosFromDirectionalIndex(BlockPos start, int index, Vector3i right, Vector3i up) throws RaceGateException {
    Vector3i left = new Vector3i(-right.getX(), -right.getY(), -right.getZ());
    Vector3i down = new Vector3i(-up.getX(), -up.getY(), -up.getZ());
    BlockPos result = new BlockPos(start.getX(), start.getY(), start.getZ());
    if (index == 0) {
      return result.offset(right);
    } else if (index == 1) {
      return result.offset(right).offset(up);
    } else if (index == 2) {
      return result.offset(up);
    } else if (index == 3) {
      return result.offset(left).offset(up);
    } else if (index == 4) {
      return result.offset(left);
    } else if (index == 5) {
      return result.offset(left).offset(down);
    } else if (index == 6) {
      return result.offset(down);
    } else if (index == 7) {
      return result.offset(right).offset(down);
    } else {
      throw new RaceGateException("Unknown directional index.");
    }
  }

  private static float angleBetweenWithSign(Vector3f testToCenter, Vector3f surroundingBlockDirection, Vector3f normal) {
    float angle = testToCenter.angleBetween(surroundingBlockDirection);

    float sign;
    Vector3f cross = testToCenter.cross(surroundingBlockDirection);
    if (cross.length() < 0.001f) {
      // This happens when the angle is 0 or 180.
      sign = 1f;
    } else {
      float dot = cross.dot(normal);
      sign = FastMath.sign(dot);
    }

    return sign * angle;
  }

  private static BlockPos convertToBlockPos(Vector3f pos) {
    return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
  }
}
