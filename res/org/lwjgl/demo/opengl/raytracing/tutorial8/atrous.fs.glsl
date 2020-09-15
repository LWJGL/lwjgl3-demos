/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
/**
 * This is a 1:1 implementation/copy of the GLSL algorithm shown at the
 * end of the 2010 paper "Edge-Avoiding À-Trous Wavelet Transform for
 * fast Global Illumination Filtering"
 *
 * https://jo.dreggn.org/home/2010_atrous.pdf
 */
#version 430 core

uniform sampler2D colorMap;
uniform sampler2D normalMap;
uniform sampler2D depthMap;

uniform float c_phi;
uniform float n_phi;
uniform float p_phi;
uniform int stepwidth;

#define KERNEL_SIZE 9
uniform float kernel[KERNEL_SIZE];
uniform ivec2 offset[KERNEL_SIZE];

in vec2 texcoord;
out vec4 color;

void main(void) {
  vec3 sum = vec3(0.0);
  ivec2 tx = ivec2(texcoord);
  vec4 cval = texelFetch(colorMap, tx, 0);
  float sampleFrame = cval.a;
  float sf2 = sampleFrame*sampleFrame;
  vec3 nval = texelFetch(normalMap, tx, 0).xyz;
  float pval = texelFetch(depthMap, tx, 0).r;
  if (isnan(pval)) {
    color = cval;
    return;
  }
  float cum_w = 0.0;
  for (int i = 0; i < KERNEL_SIZE; i++) {
    ivec2 uv = tx + offset[i] * stepwidth;
    float ptmp = texelFetch(depthMap, uv, 0).r;
    if (isnan(ptmp))
      continue;
    vec3 ntmp = texelFetch(normalMap, uv, 0).xyz;
    float n_w = dot(nval, ntmp);
    if (n_w < 1E-3)
      continue;
    vec4 ctmp = texelFetch(colorMap, uv, 0);
    vec3 t = cval.rgb - ctmp.rgb;
    float c_w = max(min(1.0 - dot(t, t) / c_phi * sf2, 1.0), 0.0);
    float pt = abs(pval - ptmp);
    float p_w = max(min(1.0 - pt/p_phi, 1.0), 0.0);
    float weight = c_w * p_w * n_w * kernel[i];
    sum += ctmp.rgb * weight;
    cum_w += weight;
  }
  color = vec4(sum / cum_w, sampleFrame);
}
