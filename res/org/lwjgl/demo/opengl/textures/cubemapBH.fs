/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

uniform samplerCube tex;
uniform vec3 cameraPosition;

uniform vec3 blackholePosition;
uniform float blackholeSize;
uniform float debug;

varying vec3 dir;

// Could probably be uniforms
#define blackholeStrength 4.0
#define blackholeHorizonSharpness 1

float interpolate(float edge0, float edge1, float x) {
  int i;
  float c = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
  for (i = 0; i < blackholeHorizonSharpness; i++) {
    c = c * c * (3.0 - 2.0 * c); // <- smoothstep
  }
  return c;
}

vec3 planeIntersect() {
	vec3 planeNormal = normalize(cameraPosition - blackholePosition);
	float t = -dot(planeNormal, cameraPosition - blackholePosition) / dot(planeNormal, dir);
	return cameraPosition + dir * t;
}

vec4 distortion(void) {
  // Compute direction vector from the black hole to the intersection point of the 
  // view direction with the plane of the black hole (directed towards the camera). 
  vec3 perp = planeIntersect() - blackholePosition;
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
  vec3 ndir = normalize(dir);
  ndir -= blackholeStrength * perp;
  return vec4(ndir, val);
}

void main(void) {
  vec4 color = vec4(1.0);
  if (debug > 0.0) {
    color = vec4(0.9, 1.7, 0.9, 1.0);
  }
  vec4 dist = distortion();
  gl_FragColor = color * textureCube(tex, dist.xyz) * dist.w;
}
