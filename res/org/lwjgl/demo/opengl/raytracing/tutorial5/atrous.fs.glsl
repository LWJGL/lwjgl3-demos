/**
 * This is a 1:1 implementation/copy of the GLSL algorithm shown at the
 * end of the 2010 paper "Edge-Avoiding À-Trous Wavelet Transform for
 * fast Global Illumination Filtering"
 *
 * https://jo.dreggn.org/home/2010_atrous.pdf
 */
#version 330 core

uniform sampler2D colorMap;
uniform sampler2D normalMap;
uniform sampler2D posMap;
uniform float c_phi;
uniform float n_phi;
uniform float p_phi;
uniform float stepwidth;
uniform float kernel[25];
uniform vec2 offset[25];

in vec2 texcoord;
out vec4 color;

void main(void) {
  vec4 sum = vec4(0.0);
  ivec2 size = textureSize(colorMap, 0);
  vec2 step = vec2(1.0)/vec2(size); // resolution
  vec4 cval = texture(colorMap, texcoord);
  vec4 nval = texture(normalMap, texcoord);
  vec4 pval = texture(posMap, texcoord);
  float cum_w = 0.0;
  for(int i = 0; i < 25; i++) {
    vec2 uv = texcoord + offset[i] * step * stepwidth;
    vec4 ctmp = texture(colorMap, uv);
    vec4 t = cval - ctmp;
    float c_w = min(exp(-dot(t, t) / c_phi), 1.0);
    vec4 ntmp = texture(normalMap, uv);
    t = nval - ntmp;
    float dist2 = max(dot(t, t) / (stepwidth * stepwidth), 0.0);
    float n_w = min(exp(-dist2 / n_phi), 1.0);
    vec4 ptmp = texture(posMap, uv);
    t = pval - ptmp;
    float p_w = min(exp(-dot(t, t) / p_phi), 1.0);
    float weight = c_w * n_w * p_w;
    sum += ctmp * weight * kernel[i];
    cum_w += weight * kernel[i];
  }
  color = sum / cum_w;
}
