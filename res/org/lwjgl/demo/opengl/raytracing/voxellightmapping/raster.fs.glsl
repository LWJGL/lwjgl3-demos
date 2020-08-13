/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D lightmap;
uniform samplerBuffer materials;

centroid in vec2 lightmapCoords_varying;
flat in int matIndex;

layout(location=0) out vec4 color;

void main(void) {
  vec3 col = texelFetch(materials, matIndex).rgb;
  vec3 lm = vec4(texture(lightmap, lightmapCoords_varying)).rgb;
  color = vec4(lm * col, 1.0);
}
