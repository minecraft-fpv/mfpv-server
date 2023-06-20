package com.gluecode.fpvdrone.server.racing;

import com.jme3.math.FastMath;
import org.jetbrains.annotations.NotNull;

/*
This class is used to sort a list of floats but retain a mapping back to the
unsorted array.

Also, it sorts the absolute value of the floats so,
[-6, -5, -4, 1, 2, 3] becomes [1, 2, 3, -4, -5, -6]
* */
public class IndexedAbsFloat implements Comparable<IndexedAbsFloat> {
  public float value;
  public int index;

  public IndexedAbsFloat(float value, int index) {
    this.value = value;
    this.index = index;
  }

  @Override
  public int compareTo(@NotNull IndexedAbsFloat o) {
    if (FastMath.abs(FastMath.abs(this.value) - FastMath.abs(o.value)) < 0.001f) {
      return 0;
    } else if (FastMath.abs(this.value) > FastMath.abs(o.value)) {
      return 1;
    } else {
      return -1;
    }
  }
}
