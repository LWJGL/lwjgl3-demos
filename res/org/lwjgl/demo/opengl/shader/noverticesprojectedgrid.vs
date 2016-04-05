#version 130

#define MAX_HEIGHT 0.2

uniform mat4 transform;
uniform vec4 intersections[4];

uniform ivec2 size;
uniform float time;
const ivec2 off[6] = ivec2[] ( ivec2(0,0), ivec2(1,0), ivec2(1,1), ivec2(1,1), ivec2(0,1), ivec2(0,0) );

void main(void) {
  // Build grid vertex
  int vertIdx = gl_VertexID % 6;
  int gridIdx = gl_VertexID / 6;
  vec2 pos = vec2(gridIdx / size.x, gridIdx % size.x);
  pos = (pos + vec2(off[vertIdx])) / vec2(size).yx;
  // Interpolate homogeneous corner intersection coordinates
  vec4 isect = mix(mix(intersections[0], intersections[2], pos.y), mix(intersections[1], intersections[3], pos.y), pos.x);
  vec3 isectWorld = isect.xyz / isect.w;
  isect.y += sin(time + isectWorld.x) * MAX_HEIGHT * isect.w;
  gl_Position = transform * isect;
}
