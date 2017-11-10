/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 330 core

#define TWO_PI 6.28318530718

/**
 * http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html
 */
uint hash(uint x) {
  x += (x << 10u);
  x ^= (x >> 6u);
  x += (x << 3u);
  x ^= (x >> 11u);
  x += (x << 15u);
  return x;
}

/**
 * http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html
 */
uint hash(uvec3 v) {
  return hash(v.x ^ hash(v.y) ^ hash(v.z));
}

/**
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
 */
float random(vec3 f) {
  uint mantissaMask = 0x007FFFFFu;
  uint one = 0x3F800000u;
  uint h = hash(floatBitsToUint(f));
  return uintBitsToFloat((h & mantissaMask) | one) - 1.0;
}

/**
 * Generate a uniformly distributed random vector on the hemisphere
 * around the given normal vector 'n'.
 *
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
