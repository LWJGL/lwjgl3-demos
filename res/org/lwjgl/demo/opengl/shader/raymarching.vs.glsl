#version 330 core

layout (location = 0) in vec3 pos;

uniform mat4 projection;
uniform mat4 view;
uniform vec3 camPosition;

out vec3 o;
out vec3 d;

void main(void) {
  o = pos * 0.5 + vec3(0.5);
  d = pos - camPosition;
  gl_Position = projection * view * vec4(pos, 1.0);
}
