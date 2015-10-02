#version 150

const vec3 edgeColor = vec3(0.0, 0.0, 0.0);
const vec3 faceColor = vec3(0.2, 0.5, 0.8);

in VertexData {
  noperspective vec3 distance;
} vVertexIn;

out vec4 color;

void main(void) {
  // determine frag distance to closest edge
  float fNearest = min(min(vVertexIn.distance[0], vVertexIn.distance[1]), vVertexIn.distance[2]);
  float fEdgeIntensity = clamp(exp2(-0.2 * fNearest * fNearest), 0.0, 1.0);

  // blend between edge color and face color
  color = vec4(mix(faceColor, edgeColor, fEdgeIntensity), 1.0);
}
