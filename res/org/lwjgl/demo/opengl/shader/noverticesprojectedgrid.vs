#version 130

#define MAX_HEIGHT 0.2

uniform mat4 transform;
uniform mat4 projector;
uniform ivec2 size;
uniform float time;
const ivec2 off[6] = ivec2[] ( ivec2(0,0), ivec2(1,0), ivec2(1,1), ivec2(1,1), ivec2(0,1), ivec2(0,0) );

void main(void) {
  int vertIdx = gl_VertexID % 6;
  int gridIdx = gl_VertexID / 6;
  vec2 pos = vec2(gridIdx / size.x, gridIdx % size.x);
  pos = (pos + vec2(off[vertIdx])) / vec2(size).yx;
  vec4 p0 = projector * vec4(pos.x, pos.y, -1.0, 1.0);
  vec4 p1 = projector * vec4(pos.x, pos.y, +1.0, 1.0);
  float t = -p0.y / (p1.y - p0.y);
  vec4 isect = p0 + (p1 - p0) * t;
  vec3 isectWorld = isect.xyz / isect.w;
  isect.y += sin(time + isectWorld.x) * MAX_HEIGHT * isect.w;
  gl_Position = transform * isect;
}
