#version 130

uniform int lod = 5;
uniform mat4 transform;

#define NUM_POINTS 6
const vec3 points[NUM_POINTS] = vec3[] (
  vec3(-1.0, 2.0, 0.0),
  vec3( 5.0, 2.0, 2.0),
  vec3(-5.0, 0.0, 0.0),
  vec3(-3.0, 5.0,-2.0),
  vec3( 2.0, 2.0, 5.0),
  vec3(-5.0, 0.0, 0.0)
);

vec3 getPoint(int i) {
  if (i < 0)
    i = 0;
  else if (i >= NUM_POINTS)
    i = NUM_POINTS - 1;
  return points[i];
}

void main(void) {
  int i = gl_VertexID % lod;
  int segment = gl_VertexID / lod;
  int start = -3 + segment;
  float t = float(i) / float(lod);
  float t2 = t * t;
  float t3 = t2 * t;
  float it = 1.0 - t;
  float w0 = it * it * it / 6.0;
  float w1 = (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
  float w2 = (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
  float w3 =  t3 / 6.0;
  vec3 p = w0 * getPoint(start + 0) +
           w1 * getPoint(start + 1) +
           w2 * getPoint(start + 2) +
           w3 * getPoint(start + 3);
  gl_Position = transform * vec4(p, 1.0);
}
