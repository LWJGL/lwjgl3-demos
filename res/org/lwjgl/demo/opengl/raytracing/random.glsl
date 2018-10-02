/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

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
 * Generate a random value in [-1..+1).
 * 
 * The distribution MUST be really uniform and exhibit NO pattern at all,
 * because it is heavily used to generate random sample directions for various
 * things, and if the random function contains the slightest pattern, it will
 * be visible in the final image.
 * 
 * In the GLSL world, the function presented in the first answer to:
 * 
 *   http://stackoverflow.com/questions/4200224/random-noise-functions-for-glsl
 * 
 * is often used, but that is not a good function, as it has problems with
 * floating point precision and is very sensitive to the seed value, resulting
 * in visible patterns for small and large seeds.
 * 
 * The best implementation (requiring GLSL 330, though) that I found over
 * time is actually this:
 * 
 *   http://amindforeverprogramming.blogspot.de/2013/07/random-floats-in-glsl-330.html
 */
float random(vec2 pos, float time) {
  uint mantissaMask = 0x007FFFFFu;
  uint one = 0x3F800000u;
  uvec3 u = floatBitsToUint(vec3(pos, time));
  uint h = hash3(u.x, u.y, u.z);
  return uintBitsToFloat((h & mantissaMask) | one) - 1.0;
}
