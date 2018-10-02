/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

attribute vec3 position;
attribute float visible;

varying float vertexVisible;

void main(void) {
  vertexVisible = visible;
  gl_Position = projMatrix * viewMatrix * vec4(position, 1.0);
}
