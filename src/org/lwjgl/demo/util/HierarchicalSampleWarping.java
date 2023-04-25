/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

import org.joml.Vector2f;
import org.joml.Vector2i;

/**
 * Implements hierarchical sample warping as first proposed in the paper
 * "Wavelet Importance: Efficiently Evaluating Products of Complex Functions" by Clarberg et al.
 * <p>
 * This can be used for importance sampling of discrete distributions represented by a texture mip chain, such as for
 * image based lighting or for textures used as an irradiance cache as used by EA's SEED project shown in the
 * SIGGRAPH 2021 presentation "Global Illumination Based on Surfels".
 * <p>
 * See:
 * <ul>
 * <li><a href="https://link.springer.com/content/pdf/10.1007/978-1-4842-4427-2_16.pdf">section 16.4.2.3 HIERARCHICAL TRANSFORMATION in chapter "Transformations Zoo" of "Ray Tracing Gems".</a></li>
 * <li><a href="http://graphics.ucsd.edu/~henrik/papers/wavelet_importance_sampling.pdf">"Wavelet Importance: Efficiently Evaluating Products of Complex Functions" by Clarberg et al.</a></li>
 * <li><a href="https://www.ea.com/seed/news/siggraph21-global-illumination-surfels">SIGGRAPH 21: Global Illumination Based on Surfels</a></li>
 * </ul>
 *
 * @author Kai Burjack
 */
public class HierarchicalSampleWarping {
  private static int idx(int x, int y, int lod) {
    return x + y * (2 << lod);
  }
  private static float mix(float a, float b, float v) {
    return a*(1-v) + b*v;
  }
  private static float step(float edge, float v) {
    return v < edge ? 0 : 1;
  }

  public static Vector2i sample(float[][] levels, Vector2f u, float[] pdf) {
    int x = 0, y = 0;
    pdf[0] = 1.0f;
    for (int lod = 0; lod < levels.length; lod++) {
      // here, lod=0 is the _highest_ lod level (with the smallest image dimension)
      // so we "ascend" the lod levels from the coarsest to the finest/biggest level.
      x <<= 1; y <<= 1;
      float s0 = levels[lod][idx(x  ,y  ,lod)], s1 = levels[lod][idx(x+1,y  ,lod)];
      float s2 = levels[lod][idx(x  ,y+1,lod)], s3 = levels[lod][idx(x+1,y+1,lod)];
      float left = s0 + s2, right = s1 + s3;
      float pLeft = left / (left + right);
      float uxFactor = step(pLeft, u.x);
      float pLower = mix(s0 / left, s1 / right, uxFactor);
      float uyFactor = step(pLower, u.y);
      float uxDen = mix(pLeft, 1.0f - pLeft, uxFactor);
      float uyDen = mix(pLower, 1.0f - pLower, uyFactor);
      u.x = mix(u.x, u.x - pLeft, uxFactor) / uxDen;
      u.y = mix(u.y, u.y - pLower, uyFactor) / uyDen;
      pdf[0] *= uxDen * uyDen;
      if (uxFactor == 1) x++;
      if (uyFactor == 1) y++;
    }
    return new Vector2i(x, y);
  }
}
