#version 110

uniform samplerCube tex;
uniform vec3 cameraPosition;

varying vec3 dir;

// Could probably be uniforms
const vec3 blackhole = vec3(0.0, 0.0, 0.0);
const float blackholeSize = 2.0;

vec4 distortion(void) {
  vec3 ndir = normalize(dir);
  vec3 perp = cross(ndir, blackhole - cameraPosition);
  float distance = length(perp);
  float val = smoothstep(0.0, blackholeSize, distance);
  val = pow(val, 4.0); // <- harder edge than smoothstep
  ndir += perp * (1.0 - val);
  return vec4(ndir, val);
}

void main(void) {
  vec4 dist = distortion();
  gl_FragColor = textureCube(tex, dist.xyz) * dist.w;
}
