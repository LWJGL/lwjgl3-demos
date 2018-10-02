#version 110

attribute vec2 position;

varying vec2 coord;

void main(void) {
  coord = position * 0.5 + vec2(0.5, 0.5);
  gl_Position = vec4(position, 0.0, 1.0);
}
