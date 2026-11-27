package org.frost.vision.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mínimo (parseo a Map/List/Double/String/Boolean/null y escritura), sin dependencias. */
public final class Json {
  private final String s;
  private int i;

  private Json(String s) { this.s = s; }

  public static Object parse(String text) {
    Json j = new Json(text);
    j.ws();
    Object v = j.value();
    j.ws();
    if (j.i != text.length()) throw new IllegalArgumentException("JSON: basura al final en " + j.i);
    return v;
  }

  private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

  private Object value() {
    char c = s.charAt(i);
    switch (c) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': i += 4; return Boolean.TRUE;
      case 'f': i += 5; return Boolean.FALSE;
      case 'n': i += 4; return null;
      default: return number();
    }
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    i++;
    ws();
    if (s.charAt(i) == '}') { i++; return m; }
    while (true) {
      ws();
      String k = string();
      ws();
      i++; // :
      ws();
      m.put(k, value());
      ws();
      char c = s.charAt(i++);
      if (c == '}') return m;
    }
  }

  private List<Object> array() {
    List<Object> a = new ArrayList<>();
    i++;
    ws();
    if (s.charAt(i) == ']') { i++; return a; }
    while (true) {
      ws();
      a.add(value());
      ws();
      char c = s.charAt(i++);
      if (c == ']') return a;
    }
  }

  private String string() {
    StringBuilder b = new StringBuilder();
    i++;
    while (true) {
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c == '\\') {
        char e = s.charAt(i++);
        switch (e) {
          case 'n': b.append('\n'); break;
          case 't': b.append('\t'); break;
          case 'r': b.append('\r'); break;
          case 'b': b.append('\b'); break;
          case 'f': b.append('\f'); break;
          case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
          default: b.append(e);
        }
      } else b.append(c);
    }
  }

  private Double number() {
    int st = i;
    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
    return Double.parseDouble(s.substring(st, i));
  }

  public static String quote(String v) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : v.toCharArray()) {
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
      }
    }
    return b.append('"').toString();
  }
}
