/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform samplerCube tex;
uniform vec3 cameraPosition;

uniform vec3 blackholePosition;
uniform float blackholeSize;
uniform float debug;

varying vec3 dir;

// Could probably be uniforms
#define blackholeStrength 6.0
#define blackholeHorizonSharpness 2

float interpolate(float edge0, float edge1, float x) {
  int i;
  float c = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
  for (i = 0; i < blackholeHorizonSharpness; i++) {
    c = c * c * (3.0 - 2.0 * c); // <- smoothstep
  }
  return c;
}

vec3 planeIntersect() {
  vec3 planeNormal = cameraPosition - blackholePosition;
  float t = -dot(planeNormal, planeNormal) / dot(planeNormal, dir);
  return cameraPosition + dir * t;
}

/**
 * http://lolengine.net/blog/2013/07/27/rgb-to-hsv-in-glsl
 */
vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = c.g < c.b ? vec4(c.bg, K.wz) : vec4(c.gb, K.xy);
  vec4 q = c.r < p.x ? vec4(p.xyw, c.r) : vec4(c.r, p.yzx);
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

/**
 * http://lolengine.net/blog/2013/07/27/rgb-to-hsv-in-glsl
 */
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

/**
 * Add some fake redshift to the light based on the distance to the black hole.
 */
vec4 redshift(vec4 col, float w) {
  vec3 hsv = rgb2hsv(col.rgb);
  hsv.r *= w*w*w*w; // <- shift towards red (which is hue = 0).
  return vec4(hsv2rgb(hsv), 1.0);
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
  gl_FragColor = color * redshift(textureCube(tex, dist.xyz), dist.w) * dist.w;
}
