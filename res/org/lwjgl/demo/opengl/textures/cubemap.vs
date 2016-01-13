#version 110

uniform mat4 invViewProj;
uniform vec3 cameraPosition;

varying vec3 dir;

void main(void) {
  vec4 tmp = invViewProj * vec4(gl_Vertex.xy, 0.0, 1.0);
  dir = tmp.xyz / tmp.w - cameraPosition;
  gl_Position = gl_Vertex;
}
