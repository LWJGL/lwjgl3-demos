/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

#define TWO_PI 6.28318530718

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
 * http://www.jcgt.org/published/0009/03/02/
 */
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

/**
 * Generate a floating-point pseudo-random number vector in [0, 1).
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
 * @param f some input vector of numbers to generate a single
 *          pseudo-random number from
 * @returns a vector of pseudo-random numbers in [0, 1)
 */
vec3 random3(vec3 f) {
  return uintBitsToFloat((pcg3d(floatBitsToUint(f)) & 0x007FFFFFu) | 0x3F800000u) - 1.0;
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
 * Generate a uniformly distributed random vector on the hemisphere
 * around the given normal vector 'n'.
 *
 * http://mathworld.wolfram.com/SpherePointPicking.html
 *
 * @param n the normal vector determining the direction of the
 *          hemisphere
 * @param rand a vector of two floating-point pseudo-random numbers
 * @returns the random hemisphere vector plus its probability density
 *          value
 */
vec3 randomHemispherePoint(vec3 n, spatialrand rand) {
  vec3 v = isotropic(rand.x, rand.y);
  return dot(n, v) < 0.0 ? -v : v;
}
