#version 300 es
// Modos: 0 azul AR (translúcido), 1 color real, 2 sombreado gris, 3 semáforo, 4 textura (foto clave; sin ella, color).
// Semáforo: compara la distancia de la superficie guardada con la profundidad raw EN VIVO:
//   verde = coincide (±uTol), rojo = la cámara ve a través (lo medido está más lejos), gris = sin dato u oculto.
precision highp float;
precision highp int;
in vec3 vWorld;
in vec3 vNor;
in vec3 vCol;
in vec2 vUv;
uniform int uMode;
uniform float uAlpha;
uniform highp sampler2D uLive;  // R32F metros, 0 = sin dato
uniform mat4 uLiveInv;          // mundo (sesión) -> cámara del sensor
uniform vec4 uLiveK;            // fx, fy, cx, cy (píxeles de la profundidad)
uniform ivec2 uLiveSize;
uniform float uTol;
uniform sampler2D uTex;          // foto clave (sRGB, se muestra tal cual)
uniform int uHasTex;
out vec4 outColor;

vec3 toSrgb(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)); }

void main() {
  vec3 n = normalize(vNor);
  float lam = 0.55 + 0.45 * abs(dot(n, normalize(vec3(0.35, 0.9, 0.45))));
  if (uMode == 0) {
    outColor = vec4(vec3(0.18, 0.44, 0.92) * lam, uAlpha);
  } else if (uMode == 1) {
    outColor = vec4(toSrgb(vCol), uAlpha);
  } else if (uMode == 4) {
    outColor = uHasTex == 1 ? vec4(texture(uTex, vUv).rgb, uAlpha) : vec4(toSrgb(vCol), uAlpha);
  } else if (uMode == 2) {
    outColor = vec4(toSrgb(vec3(0.62) * lam), uAlpha);
  } else {
    vec3 col = vec3(0.55);
    vec4 c = uLiveInv * vec4(vWorld, 1.0);
    float z = -c.z;
    if (z > 0.1) {
      int iu = int(floor(uLiveK.x * c.x / z + uLiveK.z));
      int iv = int(floor(uLiveK.w - uLiveK.y * c.y / z));
      if (iu >= 0 && iv >= 0 && iu < uLiveSize.x && iv < uLiveSize.y) {
        float d = texelFetch(uLive, ivec2(iu, iv), 0).r;
        if (d > 0.0) {
          float diff = d - z;
          if (abs(diff) < uTol) col = vec3(0.15, 0.85, 0.3);
          else if (diff > uTol) col = vec3(0.95, 0.2, 0.18);
        }
      }
    }
    outColor = vec4(col * lam, uAlpha);
  }
}
