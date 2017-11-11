/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 330 core

#define TWO_PI 6.28318530718

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
 * Generate a floating-point pseudo-random number in [0, 1).
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
 * @returns the random hemisphere vector
 */
vec3 randomHemispherePoint(vec3 n, vec3 rand) {
  float ang1 = rand.x * TWO_PI;
  float u = rand.y * 2.0 - 1.0;
  float s = sqrt(1.0 - u * u);
  vec3 v = vec3(s * cos(ang1), s * sin(ang1), u);
  return v * sign(dot(v, n));
}

