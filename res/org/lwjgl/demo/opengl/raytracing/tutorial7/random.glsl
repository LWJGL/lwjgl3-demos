/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)

/*
 * Define the type of a random variable/vector which is used to compute
 * spatial values (positions, directions, angles, etc.).
 * Actually this should have been a typedef, but we can't do this in
 * GLSL.
 */
#define spatialrand vec2

/**
 * Compute an arbitrary vector orthogonal to any vector 'v'.
 *
 * http://lolengine.net/blog/2013/09/21/picking-orthogonal-vector-combing-coconuts
 *
 * @param v the vector to compute an orthogonal vector from
 * @returns the vector orthogonal to 'v'
 */
vec3 ortho(vec3 v) {
  return mix(vec3(-v.y, v.x, 0.0), vec3(0.0, -v.z, v.y), step(abs(v.x), abs(v.z)));
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
 * Generate a floating-point pseudo-random number in [0, 1).
 *
 * With Monte Carlo sampling we need random numbers to generate random
 * vectors for shooting rays.
 * This function takes a vector of three arbitrary floating-point
 * numbers and based on those numbers returns a pseudo-random number in
 * the range [0..1).
 * Since in GLSL we have no other state/entropy than what we input as
 * uniforms or compute from varying variables (e.g. the invocation id of
 * the current work item), any "pseudo-random" number will necessarily
 * only be some kind of hash over the input.
 * This function however has very very good properties exhibiting no
 * patterns in the distribution of the random numbers, no matter the
 * magnitude of the input values.
 * 
 * http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html
 *
 * @param f some input vector of numbers to generate a single
 *          pseudo-random number from
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
 * Transform the given vector 'v' from its local frame into the
 * orthonormal basis with +Z = z.
 *
 * @param v the vector to transform
 * @param z the direction to transform +Z into
 * @returns the vector in the new basis
 */
vec3 around(vec3 v, vec3 z) {
  vec3 t = ortho(z), b = cross(z, t);
  return fma(t, vec3(v.x), fma(b, vec3(v.y), z * v.z));
}

/**
 * Compute the cartesian coordinates from the values typically computed
 * when generating sample directions/angles for isotropic BRDFs.
 *
 * @param rp this is a pseudo-random number in [0, 1) representing the
 *           angle `phi` to rotate around the principal vector. For
 *           isotropic BRDFs where `phi` has the same probability of
 *           being chosen, this will always be a random number in [0, 1)
 *           that can directly be used from rand.x given to all sample
 *           direction generation functions
 * @param c this is the cosine of theta, the angle in [0, PI/2) giving
 *          the angle between the principal vector and the one to
 *          generate. Being the cosine of an angle in [0, PI/2) the
 *          value 'c' is itself in the range [0, 1)
 * @returns the cartesian direction vector
 */
vec3 isotropic(float rp, float c) {
  // sin(a) = sqrt(1.0 - cos(a)^2) , in the interval [0, PI/2] relevant for us
  float p = TWO_PI * rp, s = sqrt(1.0 - c*c);
  return vec3(cos(p) * s, sin(p) * s, c);
}

/**
 * Generate a cosine-weighted random vector on the hemisphere around the
 * given normal vector 'n'.
 *
 * The probability density of any vector is directly proportional to the
 * cosine of the angle between that vector and the given normal 'n'.
 *
 * http://www.rorydriscoll.com/2009/01/07/better-sampling/
 *
 * @param n the normal vector determining the direction of the
 *          hemisphere
 * @param rand a vector of two floating-point pseudo-random numbers
 * @returns the cosine-weighted random hemisphere vector plus its
 *          probability density value
 */
vec4 randomCosineWeightedHemispherePoint(vec3 n, spatialrand rand) {
  float c = sqrt(rand.y);
  return vec4(around(isotropic(rand.x, c), n), c * ONE_OVER_PI);
}
