/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_2PI (1.0 / TWO_PI)

/*
 * Define the type of a random variable/vector which is used to compute
 * spatial values (positions, directions, angles, etc.).
 * Actually this should have been a typedef, but we can't do this in
 * GLSL.
 */
#define spatialrand vec2

/**
 * Compute an arbitrary unit vector orthogonal to any vector 'v'.
 *
 * http://lolengine.net/blog/2013/09/21/picking-orthogonal-vector-combing-coconuts
 *
 * @param v the vector to compute an orthogonal unit vector from
 * @returns the unit vector orthogonal to 'v'
 */
vec3 ortho(vec3 v) {
  return normalize(abs(v.x) > abs(v.z) ? vec3(-v.y, v.x, 0.0) : vec3(0.0, -v.z, v.y));
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
  return t * v.x + b * v.y + z * v.z;
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
vec4 randomHemispherePoint(vec3 n, spatialrand rand) {
  return vec4(around(isotropic(rand.x, rand.y), n), ONE_OVER_2PI);
}
/**
 * Compute the probability density value of randomHemispherePoint()
 * generating the vector 'v'.
 *
 * @param n the normal vector determining the direction of the
 *          hemisphere
 * @param v the vector to compute the pdf of
 * @returns pdf(v) for the uniform hemisphere distribution
 */
float hemisphereProbability(vec3 n, vec3 v) {
  return step(0.0, dot(v, n)) * ONE_OVER_2PI;
}

/**
 * Generate a random vector uniformly distributed over the area of the rectangle
 * with corner point 'c' and extents 'x' and 'y'.
 *
 * @param p the origin/point to sample from
 * @param c the corner of the rectangle
 * @param x the local X axis of the rectangle
 * @param y the local Y axis of the rectangle
 * @param rand a vector of two pseudo-random numbers
 * @returns the random sample vector
 */
vec4 randomRectanglePoint(vec3 p, vec3 c, vec3 x, vec3 y, spatialrand rand) {
  vec3 s = c + rand.x * x + rand.y * y, n = cross(x, y);
  float a2 = dot(n, n), aInv = inversesqrt(a2);
  n *= aInv;
  vec3 sp = p - s;
  float len2 = dot(sp, sp);
  vec3 d = sp * inversesqrt(len2);
  return vec4(-d, aInv * len2 / max(0.0, dot(d, n)));
}

/**
 * Determine whether the point 'p' lies inside of the rectangle
 * with corner 'c' and spanning vectors 'x' and 'y'.
 *
 * More precisely, this function determines whether the projection
 * of the point 'p' onto the plane with point 'c' and spanning vectors
 * 'x' and 'y' is within the bounds of the rectangle defined by the
 * corner point 'c' and the directions and lengths of 'x' and 'y'.
 *
 * @param p the point to test
 * @param c the corner of the rectangle
 * @param x the local X axis of the rectangle
 * @param y the local Y axis of the rectangle
 * @returns true iff 'p' lies inside of the rectangle; false otherwise
 */
bool inrect(vec3 p, vec3 c, vec3 x, vec3 y) {
  vec2 xyp = vec2(dot(p-c, x), dot(p-c, y));
  return all(greaterThanEqual(xyp, vec2(0.0)))
      && all(lessThan(xyp, vec2(dot(x,x), dot(y,y))));
}

/**
 * Compute the probability density value of randomRectanglePoint()
 * generating the vector 'v'.
 *
 * @param p the origin/point to sample from
 * @param c the corner of the rectangle
 * @param x the local X axis of the rectangle
 * @param y the local Y axis of the rectangle
 * @param v the vector to compute the pdf of
 * @returns pdf(v) for the rectangle distribution
 */
float rectangleProbability(vec3 p, vec3 c, vec3 x, vec3 y, vec3 v) {
  vec3 n = cross(x, y);
  float a2 = dot(n, n), aInv = inversesqrt(a2);
  n *= aInv;
  float den = dot(n, v), t = dot(c - p, n) / den;
  vec3 s = p + t * v, sp = p - s;
  float len2 = dot(sp, sp);
  vec3 d = sp * inversesqrt(len2);
  return float(den < 0.0 && t > 0.0 && inrect(s, c, x, y)) *
         aInv * len2 / dot(d, n);
}
