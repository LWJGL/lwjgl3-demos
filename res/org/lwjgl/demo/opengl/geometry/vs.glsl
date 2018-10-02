/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

in vec3 position;
in float visible;

out Vertex {
  float visible;
} vertex;

void main(void) {
  vertex.visible = visible;
  gl_Position = projMatrix * viewMatrix * vec4(position, 1.0);
}
