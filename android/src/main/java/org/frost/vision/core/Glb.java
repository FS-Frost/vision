package org.frost.vision.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * glTF binario (.glb): escritura de las mallas del escaneo (POSITION, NORMAL, COLOR_0, índices
 * uint32, material sin iluminación con color por vértice) y lectura de .glb (los de la web:
 * three.js GLTFExporter) para importarlos. Sin dependencias.
 */
public final class Glb {
  private Glb() {}

  public static byte[] write(List<MeshPart> meshes, String name) {
    StringBuilder nodes = new StringBuilder(), meshesJ = new StringBuilder(), acc = new StringBuilder(), views = new StringBuilder();
    List<byte[]> chunks = new ArrayList<>();
    int offset = 0, view = 0, a = 0, k = 0;
    for (MeshPart m : meshes) {
      if (m.isEmpty()) continue;
      int nv = m.vertexCount();
      float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
      for (int i = 0; i < nv; i++) for (int c = 0; c < 3; c++) { mn[c] = Math.min(mn[c], m.positions[i * 3 + c]); mx[c] = Math.max(mx[c], m.positions[i * 3 + c]); }
      byte[][] bufs = {floats(m.positions), floats(m.normals), floats(m.colors), ints(m.indices)};
      int[] targets = {34962, 34962, 34962, 34963};
      for (int b = 0; b < 4; b++) {
        if (views.length() > 0) views.append(',');
        views.append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(bufs[b].length).append(",\"target\":").append(targets[b]).append('}');
        chunks.add(bufs[b]);
        offset += bufs[b].length;
      }
      if (acc.length() > 0) acc.append(',');
      acc.append("{\"bufferView\":").append(view).append(",\"componentType\":5126,\"count\":").append(nv).append(",\"type\":\"VEC3\",\"min\":[")
          .append(mn[0]).append(',').append(mn[1]).append(',').append(mn[2]).append("],\"max\":[").append(mx[0]).append(',').append(mx[1]).append(',').append(mx[2]).append("]},");
      acc.append("{\"bufferView\":").append(view + 1).append(",\"componentType\":5126,\"count\":").append(nv).append(",\"type\":\"VEC3\"},");
      acc.append("{\"bufferView\":").append(view + 2).append(",\"componentType\":5126,\"count\":").append(nv).append(",\"type\":\"VEC3\"},");
      acc.append("{\"bufferView\":").append(view + 3).append(",\"componentType\":5125,\"count\":").append(m.indices.length).append(",\"type\":\"SCALAR\"}");
      if (meshesJ.length() > 0) { meshesJ.append(','); nodes.append(','); }
      meshesJ.append("{\"primitives\":[{\"attributes\":{\"POSITION\":").append(a).append(",\"NORMAL\":").append(a + 1).append(",\"COLOR_0\":").append(a + 2)
          .append("},\"indices\":").append(a + 3).append(",\"material\":0}]}");
      nodes.append("{\"mesh\":").append(k).append('}');
      view += 4; a += 4; k++;
    }
    StringBuilder nodeList = new StringBuilder();
    for (int i = 0; i < k; i++) { if (i > 0) nodeList.append(','); nodeList.append(i); }
    String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"vision-android\"},\"extensionsUsed\":[\"KHR_materials_unlit\"],"
        + "\"scene\":0,\"scenes\":[{\"name\":" + Json.quote(name) + ",\"nodes\":[" + nodeList + "]}],"
        + "\"nodes\":[" + nodes + "],\"meshes\":[" + meshesJ + "],"
        + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,1],\"metallicFactor\":0,\"roughnessFactor\":1},\"doubleSided\":true,\"extensions\":{\"KHR_materials_unlit\":{}}}],"
        + "\"accessors\":[" + acc + "],\"bufferViews\":[" + views + "],\"buffers\":[{\"byteLength\":" + offset + "}]}";
    byte[] jb = json.getBytes(StandardCharsets.UTF_8);
    int jpad = (4 - jb.length % 4) % 4;
    int bpad = (4 - offset % 4) % 4;
    int total = 12 + 8 + jb.length + jpad + 8 + offset + bpad;
    ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46546C67).putInt(2).putInt(total);
    out.putInt(jb.length + jpad).putInt(0x4E4F534A).put(jb);
    for (int i = 0; i < jpad; i++) out.put((byte) ' ');
    out.putInt(offset + bpad).putInt(0x004E4942);
    for (byte[] c : chunks) out.put(c);
    for (int i = 0; i < bpad; i++) out.put((byte) 0);
    return out.array();
  }

  private static byte[] floats(float[] v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : v) b.putFloat(f);
    return b.array();
  }

  private static byte[] ints(int[] v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int x : v) b.putInt(x);
    return b.array();
  }

  /**
   * Lee un .glb: una MeshPart por primitiva, en coordenadas de mundo. Recorre la jerarquía de nodos
   * desde la escena (GLTFExporter de la web exporta un grupo con hijos) componiendo matrix o TRS.
   */
  @SuppressWarnings("unchecked")
  public static List<MeshPart> read(byte[] data) {
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (b.getInt(0) != 0x46546C67) throw new IllegalArgumentException("no es un .glb");
    int jlen = b.getInt(12);
    String json = new String(data, 20, jlen, StandardCharsets.UTF_8);
    int binStart = 20 + jlen + 8;
    Map<String, Object> g = (Map<String, Object>) Json.parse(json);
    List<Object> accessors = (List<Object>) g.get("accessors"), viewsL = (List<Object>) g.get("bufferViews");
    List<Object> meshesL = (List<Object>) g.get("meshes");
    List<Object> nodesL = (List<Object>) g.get("nodes");
    List<MeshPart> out = new ArrayList<>();
    if (nodesL == null) return out;
    // Raíces: los nodos de la escena por defecto, o los que no son hijos de nadie.
    List<Integer> roots = new ArrayList<>();
    List<Object> scenes = (List<Object>) g.get("scenes");
    if (scenes != null && !scenes.isEmpty()) {
      int si = g.get("scene") instanceof Double ? ((Double) g.get("scene")).intValue() : 0;
      for (Object o : (List<Object>) ((Map<String, Object>) scenes.get(si)).get("nodes")) roots.add(((Double) o).intValue());
    } else {
      boolean[] child = new boolean[nodesL.size()];
      for (Object no : nodesL) {
        Object ch = ((Map<String, Object>) no).get("children");
        if (ch != null) for (Object c : (List<Object>) ch) child[((Double) c).intValue()] = true;
      }
      for (int i = 0; i < child.length; i++) if (!child[i]) roots.add(i);
    }
    List<Object[]> stack = new ArrayList<>(); // {índice, matriz del padre}
    for (int r : roots) stack.add(new Object[] {r, null});
    long key = 0;
    while (!stack.isEmpty()) {
      Object[] top = stack.remove(0); // en orden: las partes salen en el orden del archivo
      Map<String, Object> node = (Map<String, Object>) nodesL.get((Integer) top[0]);
      float[] local = nodeMatrix(node), parent = (float[]) top[1];
      float[] mat = parent == null ? local : local == null ? parent : Mat4.mul(parent, local);
      Object ch = node.get("children");
      if (ch != null) for (Object c : (List<Object>) ch) stack.add(new Object[] {((Double) c).intValue(), mat});
      if (!node.containsKey("mesh")) continue;
      Map<String, Object> mesh = (Map<String, Object>) meshesL.get(((Double) node.get("mesh")).intValue());
      for (Object po : (List<Object>) mesh.get("primitives")) {
        Map<String, Object> prim = (Map<String, Object>) po;
        Map<String, Object> attrs = (Map<String, Object>) prim.get("attributes");
        float[] pos = readFloats(b, binStart, accessors, viewsL, ((Double) attrs.get("POSITION")).intValue());
        int nv = pos.length / 3;
        float[] nor = attrs.containsKey("NORMAL") ? readFloats(b, binStart, accessors, viewsL, ((Double) attrs.get("NORMAL")).intValue()) : new float[nv * 3];
        float[] col;
        if (attrs.containsKey("COLOR_0")) {
          float[] c = readFloats(b, binStart, accessors, viewsL, ((Double) attrs.get("COLOR_0")).intValue());
          int comps = c.length / nv;
          col = new float[nv * 3];
          for (int i = 0; i < nv; i++) for (int k = 0; k < 3; k++) col[i * 3 + k] = c[i * comps + k];
        } else {
          col = new float[nv * 3];
          java.util.Arrays.fill(col, 0.6f);
        }
        int[] idx;
        if (prim.containsKey("indices")) idx = readInts(b, binStart, accessors, viewsL, ((Double) prim.get("indices")).intValue());
        else { idx = new int[nv]; for (int i = 0; i < nv; i++) idx[i] = i; }
        if (mat != null) {
          float[] p3 = new float[3];
          for (int i = 0; i < nv; i++) {
            Mat4.transform(mat, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], p3);
            System.arraycopy(p3, 0, pos, i * 3, 3);
            // normales: solo la parte lineal, renormalizadas (válido para rotación + escala uniforme)
            float nx = nor[i * 3], ny = nor[i * 3 + 1], nz = nor[i * 3 + 2];
            float x = mat[0] * nx + mat[4] * ny + mat[8] * nz, y = mat[1] * nx + mat[5] * ny + mat[9] * nz, z = mat[2] * nx + mat[6] * ny + mat[10] * nz;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 0) { nor[i * 3] = x / len; nor[i * 3 + 1] = y / len; nor[i * 3 + 2] = z / len; }
          }
        }
        out.add(new MeshPart(key++, pos, nor, col, idx));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static float[] nodeMatrix(Map<String, Object> node) {
    if (node.containsKey("matrix")) {
      List<Object> l = (List<Object>) node.get("matrix");
      float[] m = new float[16];
      for (int i = 0; i < 16; i++) m[i] = ((Double) l.get(i)).floatValue();
      return m;
    }
    if (!node.containsKey("translation") && !node.containsKey("rotation") && !node.containsKey("scale")) return null;
    float tx = 0, ty = 0, tz = 0, qx = 0, qy = 0, qz = 0, qw = 1, sx = 1, sy = 1, sz = 1;
    if (node.containsKey("translation")) {
      List<Object> l = (List<Object>) node.get("translation");
      tx = ((Double) l.get(0)).floatValue(); ty = ((Double) l.get(1)).floatValue(); tz = ((Double) l.get(2)).floatValue();
    }
    if (node.containsKey("rotation")) {
      List<Object> l = (List<Object>) node.get("rotation");
      qx = ((Double) l.get(0)).floatValue(); qy = ((Double) l.get(1)).floatValue(); qz = ((Double) l.get(2)).floatValue(); qw = ((Double) l.get(3)).floatValue();
    }
    if (node.containsKey("scale")) {
      List<Object> l = (List<Object>) node.get("scale");
      sx = ((Double) l.get(0)).floatValue(); sy = ((Double) l.get(1)).floatValue(); sz = ((Double) l.get(2)).floatValue();
    }
    // T * R * S, column-major
    float[] m = new float[16];
    m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx; m[1] = 2 * (qx * qy + qz * qw) * sx; m[2] = 2 * (qx * qz - qy * qw) * sx;
    m[4] = 2 * (qx * qy - qz * qw) * sy; m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy; m[6] = 2 * (qy * qz + qx * qw) * sy;
    m[8] = 2 * (qx * qz + qy * qw) * sz; m[9] = 2 * (qy * qz - qx * qw) * sz; m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz;
    m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1;
    return m;
  }

  @SuppressWarnings("unchecked")
  private static float[] readFloats(ByteBuffer b, int binStart, List<Object> accessors, List<Object> views, int ai) {
    Map<String, Object> acc = (Map<String, Object>) accessors.get(ai);
    Map<String, Object> view = (Map<String, Object>) views.get(((Double) acc.get("bufferView")).intValue());
    int count = ((Double) acc.get("count")).intValue();
    String type = (String) acc.get("type");
    int comps = "VEC3".equals(type) ? 3 : "VEC4".equals(type) ? 4 : "VEC2".equals(type) ? 2 : 1;
    int ctype = ((Double) acc.get("componentType")).intValue();
    boolean normalized = Boolean.TRUE.equals(acc.get("normalized"));
    int off = binStart + num(view.get("byteOffset")) + num(acc.get("byteOffset"));
    int csize = ctype == 5126 ? 4 : ctype == 5123 || ctype == 5122 ? 2 : 1;
    int stride = view.containsKey("byteStride") ? num(view.get("byteStride")) : comps * csize;
    float[] out = new float[count * comps];
    for (int i = 0; i < count; i++) for (int c = 0; c < comps; c++) {
      int p = off + i * stride + c * csize;
      float v;
      if (ctype == 5126) v = b.getFloat(p);
      else if (ctype == 5123) v = (b.getShort(p) & 0xFFFF) / (normalized ? 65535f : 1f);
      else if (ctype == 5121) v = (b.get(p) & 0xFF) / (normalized ? 255f : 1f);
      else v = b.getShort(p);
      out[i * comps + c] = v;
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static int[] readInts(ByteBuffer b, int binStart, List<Object> accessors, List<Object> views, int ai) {
    Map<String, Object> acc = (Map<String, Object>) accessors.get(ai);
    Map<String, Object> view = (Map<String, Object>) views.get(((Double) acc.get("bufferView")).intValue());
    int count = ((Double) acc.get("count")).intValue();
    int ctype = ((Double) acc.get("componentType")).intValue();
    int off = binStart + num(view.get("byteOffset")) + num(acc.get("byteOffset"));
    int[] out = new int[count];
    for (int i = 0; i < count; i++) {
      if (ctype == 5125) out[i] = b.getInt(off + i * 4);
      else if (ctype == 5123) out[i] = b.getShort(off + i * 2) & 0xFFFF;
      else out[i] = b.get(off + i) & 0xFF;
    }
    return out;
  }

  private static int num(Object o) { return o == null ? 0 : ((Double) o).intValue(); }
}
