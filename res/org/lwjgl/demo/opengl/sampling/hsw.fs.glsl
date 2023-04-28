/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D tex;
uniform float time;
uniform float blendFactor;

in vec2 texcoords;
out vec4 color;

uvec3 pcg3d(uvec3 v) {
  v = v * 1664525u + 1013904223u;
  v.x += v.y * v.z;
  v.y += v.z * v.x;
  v.z += v.x * v.y;
  v ^= v >> 16u;
  v.x += v.y * v.z;
  v.y += v.z * v.x;
  v.z += v.x * v.y;
  return v;
}
vec3 random3(vec3 f) {
  return uintBitsToFloat((pcg3d(floatBitsToUint(f)) & 0x007FFFFFu) | 0x3F800000u) - vec3(1.0);
}
float luminance(vec3 rgb) {
  return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}
vec2 importanceSampleHierarchicalWarping(sampler2D t, vec2 u) {
  int x = 0, y = 0;
  ivec2 texSize = textureSize(t, 0);
  int maxLod = int(log2(float(texSize.x)) - 0.5);
  for (int lod = maxLod; lod >= 0; lod--) {
    x <<= 1; y <<= 1;
    float x0y0 = luminance(texelFetch(t, ivec2(x+0,y+0), lod).rgb);
    float x1y0 = luminance(texelFetch(t, ivec2(x+1,y+0), lod).rgb);
    float x0y1 = luminance(texelFetch(t, ivec2(x+0,y+1), lod).rgb);
    float x1y1 = luminance(texelFetch(t, ivec2(x+1,y+1), lod).rgb);
    float left = x0y0 + x0y1, right = x1y0 + x1y1, pLeft = left / (left + right);
    float uxFactor = step(pLeft, u.x);
    float pLower = mix(x0y0 / left, x1y0 / right, uxFactor);
    float uyFactor = step(pLower, u.y);
    float uxDen = mix(pLeft, 1.0 - pLeft, uxFactor), uyDen = mix(pLower, 1.0 - pLower, uyFactor);
    u.x = mix(u.x, u.x - pLeft, uxFactor) / uxDen; u.y = mix(u.y, u.y - pLower, uyFactor) / uyDen;
    x += int(uxFactor); y += int(uyFactor);
  }
  return (vec2(x, y) + u) / vec2(texSize);
}
float distMetric(vec2 a, vec2 b) {
  return sqrt(dot(a - b, a - b));
}
void main(void) {
  vec3 c = texture(tex, texcoords).rgb;
  vec3 rnd = random3(vec3(texcoords, time));
  vec2 s = importanceSampleHierarchicalWarping(tex, rnd.xy);
  float dist = distMetric(texcoords, s);
  float distanceRandomness = 0.01 + rnd.z * 0.05;
  if (dist < distanceRandomness)
    c = vec3(15.0, 0.0, 0.0);
  //color = vec4(c, blendFactor);
  color = vec4(c, 0.5);
}
