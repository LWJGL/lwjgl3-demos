#version 110

varying vec3 viewNormal;

void main(void) {
  gl_FragColor = vec4(viewNormal, 1.0);
}
