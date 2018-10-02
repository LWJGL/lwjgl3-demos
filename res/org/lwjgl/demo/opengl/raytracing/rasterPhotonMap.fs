/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130
#extension GL_ARB_texture_cube_map_array : require

uniform samplerCubeArray cubeMaps;

in vec3 positionOnUnitCube;
flat in int level;

out vec4 color;

void main(void) {
  float r = texture(cubeMaps, vec4(positionOnUnitCube, float(level))).r;
  color = vec4(r, r, r, 1.0);
}
