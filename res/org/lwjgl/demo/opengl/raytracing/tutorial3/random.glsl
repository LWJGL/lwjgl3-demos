/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 330 core

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define FOUR_PI 12.5663706144
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define ONE_OVER_4PI (1.0 / FOUR_PI)

/**
 * http://lolengine.net/blog/2013/09/21/picking-orthogonal-vector-combing-coconuts
 */
vec3 ortho(vec3 v) {
  return abs(v.x) > abs(v.z) ? vec3(-v.y, v.x, 0.0)  : vec3(0.0, -v.z, v.y);
}

/**
 * http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html?showComment=1507064059398#c5427444543794991219
 */
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

/**
 * Generates a floating-point pseudo-random number in [0, 1).
 *
 * With Monte Carlo sampling we need random numbers to generate random vectors
 * for shooting rays.
 * This function takes a vector of three arbitrary floating-point numbers and
 * based on those numbers returns a pseudo-random number in the range [0..1).
 * Since in GLSL we have no other state/entropy than what we input as uniforms
 * or compute from varying variables (e.g. the invocation id of the current work item),
 * any "pseudo-random" number will necessarily only be some kind of hash over the
 * input.
 * This random function however has very very good properties exhibiting no patterns
 * in the distribution of the random numbers, no matter the magnitude of the input values.
 * 
 * http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html
 *
 * @param f some input vector of pseudo-random numbers to generate a single number from
 * @returns a single pseudo-random number in [0, 1)
 */
float random(vec3 f) {
  uint mantissaMask = 0x007FFFFFu;
  uint one = 0x3F800000u;
  uvec3 u = floatBitsToUint(f);
  uint h = hash3(u.x, u.y, u.z);
  return uintBitsToFloat((h & mantissaMask) | one) - 1.0;
}

/**
 * Generate a uniformly distributed random vector on the hemisphere
 * around the given normal vector 'n'.
 *
 * @param n the normal vector determining the direction of the hemisphere
 * @param rand a vector of three floating-point pseudo-random numbers
 * @returns the random hemisphere vector plus its probability density value
 */
vec4 randomHemispherePoint(vec3 n, vec3 rand) {
  float ang1 = rand.x * TWO_PI;
  float u = rand.y * 2.0 - 1.0;
  float s = sqrt(1.0 - u * u);
  vec3 v = vec3(s * cos(ang1), s * sin(ang1), u);
  return vec4(v * sign(dot(v, n)), ONE_OVER_2PI);
}

/**
 * Generate a cosine-weighted random vector on the hemisphere around
 * the given normal vector 'n'.
 *
 * The probability density of any vector is directly proportional to
 * the cosine of the angle between that vector and the given normal 'n'.
 *
 * http://www.rorydriscoll.com/2009/01/07/better-sampling/
 *
 * @param n the normal vector determining the direction of the hemisphere
 * @param rand a vector of three floating-point pseudo-random numbers
 * @returns the cosine-weighted random hemisphere vector plus its probability density value
 */
vec4 randomCosineWeightedHemispherePoint(vec3 n, vec3 rand) {
  float p = TWO_PI * rand.x, s = sqrt(1.0 - rand.y), c = sqrt(rand.y);
  vec3 t = ortho(n), b = cross(n, t);
  return vec4(t * cos(p) * s + b * sin(p) * s + n * c, c * ONE_OVER_PI);
}

/**
 * Generate Phong-weighted random vector around the given reflection vector 'r'.
 * Since the Phong BRDF has higher values when the outgoing vector
 * is close to the perfect reflection vector of the incoming vector across the normal,
 * we generate directions primarily around that reflection vector.
 *
 * http://blog.tobias-franke.eu/2014/03/30/notes_on_importance_sampling.html
 *
 * @param r the direction of perfect reflection
 * @param a the power to raise the cosine term to in the Phong model
 * @param rand some pseudo-random numbers
 * @returns the Phong-weighted random vector
 */
vec4 randomPhongWeightedHemispherePoint(vec3 r, float a, vec3 rand) {
  float p = TWO_PI * rand.x, c = pow(rand.y, 1.0 / (a + 1.0)), s = sqrt(1.0 - c * c);
  vec3 t = ortho(r), b = cross(r, t);
  return vec4(t * cos(p) * s + b * sin(p) * s + r * c, (a + 1.0) * pow(c, a) * ONE_OVER_2PI);
}
