/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D lightmap;
uniform samplerBuffer materials;
uniform bool useSimpleAo;
uniform bool useColor;

centroid in vec2 lightmapCoords_varying;
flat in int matIndex;
in vec4 ao;
in vec2 uv;

layout(location=0) out vec4 color;

void main(void) {
  vec3 aom = vec3(mix(mix(ao.x, ao.z, uv.y), mix(ao.y, ao.w, uv.y), uv.x));
  vec3 col = texelFetch(materials, matIndex).rgb;
  vec3 lm = vec4(texture(lightmap, lightmapCoords_varying)).rgb;
  color = vec4((useSimpleAo ? aom : lm) * (useColor ? col : vec3(1.0)), 1.0);
}
