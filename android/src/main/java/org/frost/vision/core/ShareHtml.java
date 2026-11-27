package org.frost.vision.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visor .html autocontenido (misma plantilla que la web, assets/share/viewer.html: three.js por CDN
 * + el .glb en base64) e importación de los .html compartidos por la web o por la app.
 * Base64 propio: java.util.Base64 exige API 26 y minSdk es 24.
 */
public final class ShareHtml {
  private ShareHtml() {}

  private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
  private static final Pattern GLB = Pattern.compile("<script id=\"glb\"[^>]*>\\s*([A-Za-z0-9+/=\\s]+?)\\s*</script>");

  public static String escapeHtml(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      switch (c) {
        case '&': b.append("&amp;"); break;
        case '<': b.append("&lt;"); break;
        case '>': b.append("&gt;"); break;
        case '"': b.append("&quot;"); break;
        default: b.append(c);
      }
    }
    return b.toString();
  }

  public static String build(String template, byte[] glb, String name) {
    String title = escapeHtml(name);
    return template.replace("{{TITLE}}", title).replace("{{GLB}}", encode(glb));
  }

  /** El .glb embebido en un .html de Vision, o null si no lo tiene. */
  public static byte[] extractGlb(String html) {
    Matcher m = GLB.matcher(html);
    if (!m.find()) return null;
    return decode(m.group(1));
  }

  public static String encode(byte[] d) {
    StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4 + d.length / 57 + 1);
    int line = 0;
    for (int i = 0; i < d.length; i += 3) {
      int b0 = d[i] & 255, b1 = i + 1 < d.length ? d[i + 1] & 255 : 0, b2 = i + 2 < d.length ? d[i + 2] & 255 : 0;
      s.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
      s.append(i + 1 < d.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
      s.append(i + 2 < d.length ? B64[b2 & 63] : '=');
      if (++line == 19) { s.append('\n'); line = 0; } // líneas de 76 caracteres
    }
    return s.toString();
  }

  public static byte[] decode(String s) {
    int[] rev = new int[128];
    java.util.Arrays.fill(rev, -1);
    for (int i = 0; i < 64; i++) rev[B64[i]] = i;
    byte[] out = new byte[s.length() * 3 / 4 + 3];
    int n = 0, acc = 0, bits = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 128 || rev[c] < 0) continue; // espacios, saltos y '='
      acc = ((acc << 6) | rev[c]) & 0xFFFFF;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out[n++] = (byte) (acc >> bits);
      }
    }
    return java.util.Arrays.copyOf(out, n);
  }
}
