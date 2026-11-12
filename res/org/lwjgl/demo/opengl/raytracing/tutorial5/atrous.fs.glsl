/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
/**
 * This is one iteration of the filter described in the 2010 paper
 * "Edge-Avoiding À-Trous Wavelet Transform for fast Global Illumination
 * Filtering" and is based on the GLSL algorithm shown at the end of
 * that paper.
 *
 * https://jo.dreggn.org/home/2010_atrous.pdf
 *
 * Every iteration computes for every pixel a weighted average of 5x5
 * samples around it, with `stepwidth - 1` pixels being skipped between
 * two samples. These are the holes ("trous") giving the algorithm its
 * name. Because the stepwidth doubles with every iteration, a few cheap
 * iterations with only 25 samples each behave like a single filter with
 * a very big kernel.
 * The weight of a sample is its weight in the 5x5 B3-spline kernel
 * times the values of the edge-stopping functions, which get smaller
 * the more the color, the normal and the position of the sample differ
 * from those of the pixel being filtered. This is what keeps the filter
 * from blurring over edges.
 *
 * The edge-stopping functions differ from the ones in the paper's GLSL
 * code in a few points, which are explained in the functions below.
 */
#version 330 core

/**
 * The image to filter. For the first iteration this is the path-traced
 * image and for every further iteration the result of the previous one.
 * Those results also contain in their alpha component how noisy the
 * path-traced image was around a pixel. See variance() below.
 */
uniform sampler2D colorMap;
/**
 * The world-space normal and position at every pixel.
 */
uniform sampler2D normalMap;
uniform sampler2D posMap;

/**
 * The squared "standard deviations" of the edge-stopping functions.
 * The smaller a value is, the faster the weight of a sample decreases
 * with a difference in color, normal, position and distance to the
 * surface plane, respectively. See Tutorial5#filter().
 */
uniform float c_phi;
uniform float n_phi;
uniform float p_phi;
uniform float d_phi;
/**
 * How much the variance of the path-traced image around a pixel widens
 * the color edge-stopping function.
 */
uniform float v_phi;
/**
 * The distance in pixels between two samples of the 5x5 kernel.
 */
uniform int stepwidth;

#define KERNEL_SIZE 25
uniform float kernel[KERNEL_SIZE];
uniform ivec2 offset[KERNEL_SIZE];

out vec4 color;

/**
 * Compute the product of the normal and position edge-stopping
 * functions for a sample. Those two tell us whether the sample is on
 * the same surface as the pixel we are filtering.
 *
 * @param nval the normal at the pixel we are filtering
 * @param pval the position at the pixel we are filtering
 * @param uv   the pixel coordinate of the sample
 * @returns the weight of the sample
 */
float geometryWeight(vec3 nval, vec3 pval, ivec2 uv) {
  /*
   * Normal edge-stopping function.
   *
   * The GLSL code in the paper divides the squared difference by the
   * squared stepwidth here. We do not do that, because it would make
   * the later iterations with their big stepwidth blur around the
   * corner between two faces.
   */
  vec3 t = nval - texelFetch(normalMap, uv, 0).xyz;
  float n_w = exp(-dot(t, t) / n_phi);
  /*
   * Position edge-stopping function.
   *
   * Like the paper, we use the distance between the two positions.
   * This limits the size of the filter in world-space, which is
   * important when looking at a surface at a grazing angle. There, a
   * few pixels on the screen can be many meters apart in the world
   * and should not all be averaged.
   *
   * But the distance alone cannot separate two nearby surfaces with
   * the same normal (think of the pillar standing right in front of
   * the back wall). A p_phi small enough for that would not let the
   * filter average over any reasonable area anymore.
   * So, we also use the distance of the sample to the plane of the
   * surface at the pixel we are filtering. This is zero for all
   * samples on the same flat surface, no matter how far away they
   * are, and so we can be very strict with it.
   */
  t = texelFetch(posMap, uv, 0).xyz - pval;
  float d = dot(nval, t);
  float p_w = exp(-dot(t, t) / p_phi - d * d / d_phi);
  return n_w * p_w;
}

/**
 * Estimate the variance of the color of a pixel in the path-traced
 * image. This is a measure for how noisy the image is at that pixel.
 *
 * We cannot know how much a pixel would differ from frame to frame from
 * just a single frame. But we can look at how much the pixels around it
 * on the same surface differ from each other in this frame. So, we
 * compute the mean of the squared colors of those pixels minus their
 * squared mean color.
 *
 * @param tx   the pixel coordinate
 * @param size the size of the image
 * @param nval the normal at the pixel
 * @param pval the position at the pixel
 * @returns the variance
 */
float variance(ivec2 tx, ivec2 size, vec3 nval, vec3 pval) {
  vec3 m1 = vec3(0.0);
  float m2 = 0.0, cum_w = 0.0;
  for (int i = 0; i < KERNEL_SIZE; i++) {
    ivec2 uv = tx + offset[i];
    if (any(lessThan(uv, ivec2(0))) || any(greaterThanEqual(uv, size)))
      continue;
    float w = geometryWeight(nval, pval, uv);
    vec3 c = texelFetch(colorMap, uv, 0).rgb;
    m1 += c * w;
    m2 += dot(c, c) * w;
    cum_w += w;
  }
  m1 /= cum_w;
  /*
   * Limit the value to what our 16-bit floating-point render target can
   * hold.
   */
  return clamp(m2 / cum_w - dot(m1, m1), 0.0, 1E4);
}

void main(void) {
  ivec2 size = textureSize(colorMap, 0);
  ivec2 tx = ivec2(gl_FragCoord.xy);
  /*
   * Color, normal and position of the pixel we are filtering.
   */
  vec4 cval = texelFetch(colorMap, tx, 0);
  vec3 nval = texelFetch(normalMap, tx, 0).xyz;
  vec3 pval = texelFetch(posMap, tx, 0).xyz;
  /*
   * And the variance of the path-traced image at that pixel. The first
   * iteration (the one without holes), whose input is the path-traced
   * image, has to compute it. It then writes it into the alpha
   * component of its result for the next iterations to use.
   */
  bool first = stepwidth == 1;
  float vval = first ? variance(tx, size, nval, pval) : cval.a;
  vec4 sum = vec4(0.0);
  float cum_w = 0.0;
  for (int i = 0; i < KERNEL_SIZE; i++) {
    ivec2 uv = tx + offset[i] * stepwidth;
    /*
     * Skip samples outside of the image. What texelFetch() returns for
     * those is undefined. Since we divide by the sum of the weights of
     * all used samples in the end, the remaining samples simply get
     * more weight.
     */
    if (any(lessThan(uv, ivec2(0))) || any(greaterThanEqual(uv, size)))
      continue;
    vec4 ctmp = texelFetch(colorMap, uv, 0);
    float vtmp = first ? vval : ctmp.a;
    /*
     * Color edge-stopping function.
     *
     * We do not compare the actual colors but what will be visible of
     * them on the screen, which is everything up to 1.0. Otherwise, a
     * single very bright pixel (a "firefly") would differ so much from
     * all of its neighbors that it only gets averaged with itself and
     * therefore survives all iterations.
     *
     * How much of a difference we tolerate does not only depend on
     * c_phi, but also on how noisy the path-traced image was around
     * the two pixels. Where it was very noisy, a difference in color
     * more likely is noise than an actual edge in the illumination.
     * This is especially the case around a firefly. Its energy can only
     * get distributed over the pixels around it, like it should, when
     * those pixels do not reject it for being too different.
     */
    vec3 t = min(cval.rgb, vec3(1.0)) - min(ctmp.rgb, vec3(1.0));
    float c_w = exp(-dot(t, t) / (c_phi + v_phi * (vval + vtmp)));
    /*
     * Combine with the weight of the normal and position edge-stopping
     * functions and with the kernel weight, and accumulate.
     * The variance gets averaged just like the color. This way, it
     * spreads together with the energy of the noisy pixels.
     */
    float weight = c_w * geometryWeight(nval, pval, uv) * kernel[i];
    sum += vec4(ctmp.rgb, vtmp) * weight;
    cum_w += weight;
  }
  /*
   * Normalize by the sum of all weights. That sum cannot be zero,
   * because the sample at the center of the kernel is the pixel itself,
   * for which all edge-stopping functions are one.
   */
  color = sum / cum_w;
}
