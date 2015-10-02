#version 150

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

in vec3 position;
in int visible;

out Vertex {
  bool visible;
} vertex;

void main(void) {
  vertex.visible = visible == 1;
  gl_Position = projMatrix * viewMatrix * vec4(position, 1.0);
}
