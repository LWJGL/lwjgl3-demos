/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 330 core

in vec4 worldNormal;
in vec4 viewPosition;

out vec4 outNormalAndDist;

void main(void) {
  float dist = length(viewPosition.xyz);
  outNormalAndDist = vec4(normalize(worldNormal.xyz), dist);
}
