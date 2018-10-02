/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform samplerCube tex;
uniform vec3 cameraPosition;

varying vec3 dir;

// Could probably be uniforms
#define blackholePosition vec3(0.0, 0.0, 0.0)
#define blackholeSize 4.0
#define blackholeStrength 4.0
#define blackholeHorizonSharpness 4

float interpolate(float edge0, float edge1, float x) {
  int i;
  float c = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
  for (i = 0; i < blackholeHorizonSharpness; i++) {
    c = c * c * (3.0 - 2.0 * c); // <- smoothstep
  }
  return c;
}

vec4 distortion(void) {
  vec3 ndir = normalize(dir);

  // Compute the vector from the point on the ray(cam, dir) with the shortest
  // distance to the blackhole and the blackhole itself.
  vec3 perp = cameraPosition + dot(blackholePosition - cameraPosition, ndir) * ndir - blackholePosition;
  float distance = length(perp);
  perp /= distance; // <- normalize it

  // Compute a mix/blend factor for how much the light will be visible.
  // It will not be visible when it is too close to the black hole.
  // We do this using a iterative smoothstep falloff.
  float val = interpolate(0.0, blackholeSize, distance);

  // Since we want the distortion to increase towards the black hole center, we
  // need to multiply with 'val', but invert val's interval because it is bigger
  // when the distance increases.
  perp *= 1.0 - val;

  // Distort our direction vector using that perpendicular vector.
  ndir -= blackholeStrength * perp;
  return vec4(ndir, val);
}

void main(void) {
  vec4 dist = distortion();
  gl_FragColor = textureCube(tex, dist.xyz) * dist.w;
}
