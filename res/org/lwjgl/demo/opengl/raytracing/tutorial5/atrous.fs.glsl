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
uniform sampler2D posMap;

uniform float c_phi;
uniform float n_phi;
uniform float p_phi;
uniform int stepwidth;

#define KERNEL_SIZE 25
uniform float kernel[KERNEL_SIZE];
uniform ivec2 offset[KERNEL_SIZE];

in vec2 texcoord;
out vec4 color;

void main(void) {
  vec4 sum = vec4(0.0);
  ivec2 tx = ivec2(texcoord * textureSize(colorMap, 0));
  vec4 cval = texelFetch(colorMap, tx, 0);
  vec4 nval = texelFetch(normalMap, tx, 0);
  vec4 pval = texelFetch(posMap, tx, 0);
  float cum_w = 0.0;
  for (int i = 0; i < KERNEL_SIZE; i++) {
    ivec2 uv = tx + offset[i] * stepwidth;
    vec4 ctmp = texelFetch(colorMap, uv, 0);
    vec4 t = cval - ctmp;
    float c_w = min(exp(-dot(t, t) / c_phi), 1.0);
    vec4 ntmp = texelFetch(normalMap, uv, 0);
    t = nval - ntmp;
    float dist2 = max(dot(t, t) / (stepwidth * stepwidth), 0.0);
    float n_w = min(exp(-dist2 / n_phi), 1.0);
    vec4 ptmp = texelFetch(posMap, uv, 0);
    t = pval - ptmp;
    float p_w = min(exp(-dot(t, t) / p_phi), 1.0);
    float weight = c_w * n_w * p_w;
    sum += ctmp * weight * kernel[i];
    cum_w += weight * kernel[i];
  }
  color = sum / cum_w;
}
