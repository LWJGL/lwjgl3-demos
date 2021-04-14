/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core
#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define spatialrand vec2
vec3 ortho(vec3 v) {
  return mix(vec3(-v.y, v.x, 0.0), vec3(0.0, -v.z, v.y), step(abs(v.x), abs(v.z)));
}
uint hash3(uint x, uint y, uint z) {
  x += x >> 11;
  x ^= x << 7;
  x += y;
  x ^= x << 3;
  x += z ^ (x >> 14);
  x ^= x << 6;
  x += x >> 15;
  x ^= x << 5;
  x += x >> 12;
  x ^= x << 9;
  return x;
}
float random3(vec3 f) {
  uint mantissaMask = 0x007FFFFFu;
  uint one = 0x3F800000u;
  uvec3 u = floatBitsToUint(f);
  uint h = hash3(u.x, u.y, u.z);
  return uintBitsToFloat((h & mantissaMask) | one) - 1.0;
}
vec3 around(vec3 v, vec3 z) {
  vec3 t = ortho(z), b = cross(z, t);
  return fma(t, vec3(v.x), fma(b, vec3(v.y), z * v.z));
}
vec3 isotropic(float rp, float c) {
  // sin(a) = sqrt(1.0 - cos(a)^2) , in the interval [0, PI/2] relevant for us
  float p = TWO_PI * rp, s = sqrt(1.0 - c*c);
  return vec3(cos(p) * s, sin(p) * s, c);
}
vec4 randomCosineWeightedHemisphereDirection(vec3 n, spatialrand rand) {
  float c = sqrt(rand.y);
  return vec4(around(isotropic(rand.x, c), n), c * ONE_OVER_PI);
}
vec4 randomPhongWeightedHemisphereDirection(vec3 r, float a, spatialrand rand) {
  float ai = 1.0 / (a + 1.0), pr = (a + 1.0) * pow(rand.y, a * ai) * ONE_OVER_2PI;
  return vec4(around(isotropic(rand.x, pow(rand.y, ai)), r), pr);
}
