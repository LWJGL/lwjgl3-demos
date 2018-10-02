#version 420 core

layout(location=0) in vec2 position;
layout(location=1) in vec3 color;

out vec3 outColor;

void main(void) {
  outColor = color;
  gl_Position = vec4(position, 0.0, 1.0);
}
