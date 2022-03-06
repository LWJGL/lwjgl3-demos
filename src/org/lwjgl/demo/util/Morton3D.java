/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

/**
 * Adapted from: http://graphics.stanford.edu/~seander/bithacks.html#InterleaveBMN
 * 
 * @author Kai Burjack
 */
public class Morton3D {
  /**
   * Encode the 5-bits X, 8-bits Y and 5-bits Z value into a 18-bits result with
   * the Y value stored into the higher order bits.
   *
   * @param x the 5-bits x value
   * @param y the 8-bits y value
   * @param z the 5-bits z value
   * @return the 18-bits morton code
   */
  public static int encode_y8_z5_x5(int x, int y, int z) {
    return encode5(y) << 2 | (y & 0xE0) << 10 | encode5(z) << 1 | encode5(x);
  }
  private static int encode5(int v) {
    v = (v | v << 8) & 0x100F;
    v = (v | v << 4) & 0x10C3;
    v = (v | v << 2) & 0x1249;
    return v;
  }

  public static int decode_y8_z5_x5_x(int c) {
    c &= 0x1249;
    c = (c ^ c >>> 2) & 0x10C3;
    c = (c ^ c >>> 4) & 0x100F;
    c = (c ^ c >>> 8) & 0x1F;
    return c;
  }

  public static int decode_y8_z5_x5_z(int c) {
    c = c >>> 1 & 0x1249;
    c = (c ^ c >>> 2) & 0x10C3;
    c = (c ^ c >>> 4) & 0x100F;
    c = (c ^ c >>> 8) & 0x1F;
    return c;
  }

  public static int decode_y8_z5_x5_y(int c) {
    int h = c >>> 10 & 0xE0;
    c = c >>> 2 & 0x1249;
    c = (c ^ c >>> 2) & 0x10C3;
    c = (c ^ c >>> 4) & 0x100F;
    c = (c ^ c >>> 8) & 0x1F;
    return h | c;
  }
}
