/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

const vec3 edgeColor = vec3(0.0, 0.0, 0.0);
const vec3 faceColor = vec3(0.95, 0.7, 0.1);

varying vec3 distance;

void main(void) {
  // determine frag distance to closest edge
  float fNearest = min(min(distance.x, distance.y), distance.z);
  float fEdgeIntensity = clamp(exp2(-0.1 * fNearest * fNearest), 0.0, 1.0);

  // blend between edge color and face color
  gl_FragColor = vec4(mix(faceColor, edgeColor, fEdgeIntensity), 1.0);
}
