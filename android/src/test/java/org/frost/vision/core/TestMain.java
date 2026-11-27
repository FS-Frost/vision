package org.frost.vision.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Tests JVM de la referencia CPU (sin JUnit: sin dependencias). Portan como PROPIEDADES los tests
 * de la web (tests/fusion.test.mjs): la integración aquí es proyectiva, no por rayo.
 * Uso: ./test.sh [filtro]
 */
public final class TestMain {
  interface T { void run() throws Exception; }

  static int passed, failed;
  static String filter;

  public static void main(String[] args) throws Exception {
    filter = args.length > 0 ? args[0] : null;
    test("pared frontal: vértices en z=-1.5, normales hacia la cámara, orientación coherente", TestMain::planeWall);
    test("sin huecos en costuras entre bloques (con y sin dilatación)", TestMain::seams);
    test("cámara rotada 90° y trasladada: pared en x = tx - 1.5", TestMain::rotatedCamera);
    test("color: mitad superior roja, inferior verde", TestMain::colorOrientation);
    test("captura: peso por distancia (corte a 3 m) y confianza", TestMain::weights);
    test("limpieza final: elimina fragmentos sueltos y conserva la pared", TestMain::cleanup);
    test("volumen: exportar e importar reproduce la malla", TestMain::volumeRoundTrip);
    test("continuar: lo congelado no cambia con 5 cm de error de alineación", TestMain::freeze);
    test("ICP: recupera yaw y traslación de una segunda sesión", TestMain::icp);
    test("relocalización global: 20 inicios al azar (sensor ideal)", TestMain::relocalization);
    test("anclas: 2-3 puntos recuperan la pose; un ancla mal marcada se detecta", TestMain::anchors);
    test("pista de inicio: startGuess recupera la transformación", TestMain::startGuess);
    test("coincidencia en vivo: la pose correcta coincide más que la girada 180°", TestMain::live);
    test("suelo: una superficie a < 0.8 m bajo la cámara no se toma como suelo", TestMain::floor);
    test("colisiones: no se atraviesan paredes y se desliza contra ellas", TestMain::collider);
    test("GLB: escribir y leer conserva vértices, colores e índices", TestMain::glb);
    test("JSON: parseo y escape", TestMain::json);
    test("metadatos y .html compartido: ida y vuelta", TestMain::metaShare);
    test("registro de frames: escribir y leer da la misma malla", TestMain::frameLog);
    System.out.println();
    System.out.println("pasaron " + passed + ", fallaron " + failed);
    if (failed > 0) System.exit(1);
  }

  static void test(String name, T t) {
    if (filter != null && !name.contains(filter)) return;
    long t0 = System.nanoTime();
    try {
      t.run();
      passed++;
      System.out.printf("ok   %s (%.0f ms)%n", name, (System.nanoTime() - t0) / 1e6);
    } catch (Throwable e) {
      failed++;
      System.out.printf("FAIL %s: %s%n", name, e);
      if (!(e instanceof AssertionError)) e.printStackTrace(System.out);
    }
  }

  static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

  // ------------------------------------------------------------ helpers

  static List<MeshPart> scan(Fusion f, float[] pose, int frames, DepthFrame frame) {
    for (int i = 0; i < frames; i++) f.integrateCpu(frame != null ? frame : TestScene.plane(1.5f, pose));
    return f.allMeshes();
  }

  static Set3 collect(List<MeshPart> meshes) {
    Set3 s = new Set3();
    for (MeshPart m : meshes) {
      int base = s.verts.size();
      for (int i = 0; i < m.positions.length; i += 3) {
        s.verts.add(new float[] {m.positions[i], m.positions[i + 1], m.positions[i + 2]});
        s.norms.add(new float[] {m.normals[i], m.normals[i + 1], m.normals[i + 2]});
        s.cols.add(new float[] {m.colors[i], m.colors[i + 1], m.colors[i + 2]});
      }
      for (int i = 0; i < m.indices.length; i += 3) s.tris.add(new int[] {m.indices[i] + base, m.indices[i + 1] + base, m.indices[i + 2] + base});
    }
    return s;
  }

  static final class Set3 {
    List<float[]> verts = new ArrayList<>(), norms = new ArrayList<>(), cols = new ArrayList<>();
    List<int[]> tris = new ArrayList<>();
    HashSet<Integer> used() { HashSet<Integer> u = new HashSet<>(); for (int[] t : tris) for (int v : t) u.add(v); return u; }
  }

  static float[] pose(double yaw, double tx, double ty, double tz) { return Mat4.yaw(yaw, tx, ty, tz); }

  // ------------------------------------------------------------ tests

  static void planeWall() {
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    Set3 s = collect(scan(f, Mat4.identity(), 5, null));
    check(s.tris.size() > 500, "pocos triángulos: " + s.tris.size());
    float maxErr = 0;
    for (int i : s.used()) {
      maxErr = Math.max(maxErr, Math.abs(s.verts.get(i)[2] + 1.5f));
      check(s.norms.get(i)[2] > 0.9f, "normal no apunta a +Z");
    }
    check(maxErr < 0.02f, "error de posición " + maxErr);
    for (int[] t : s.tris) {
      float[] a = s.verts.get(t[0]), b = s.verts.get(t[1]), c = s.verts.get(t[2]);
      float fz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
      check(fz >= -1e-6, "triángulo con orientación invertida");
    }
  }

  static int interiorBoundaryEdges(Set3 s, float margin) {
    Map<String, Integer> weld = new HashMap<>();
    int[] map = new int[s.verts.size()];
    List<float[]> pts = new ArrayList<>();
    for (int i = 0; i < s.verts.size(); i++) {
      float[] v = s.verts.get(i);
      String k = Math.round(v[0] * 1e5) + "," + Math.round(v[1] * 1e5) + "," + Math.round(v[2] * 1e5);
      Integer id = weld.get(k);
      if (id == null) { id = weld.size(); weld.put(k, id); pts.add(v); }
      map[i] = id;
    }
    Map<Long, Integer> edges = new HashMap<>();
    for (int[] t : s.tris) for (int e = 0; e < 3; e++) {
      int a = map[t[e]], b = map[t[(e + 1) % 3]];
      long k = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
      edges.merge(k, 1, Integer::sum);
    }
    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (float[] p : pts) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
    int interior = 0;
    for (Map.Entry<Long, Integer> e : edges.entrySet()) {
      if (e.getValue() != 1) continue;
      float[] a = pts.get((int) (e.getKey() >>> 32)), b = pts.get((int) (e.getKey() & 0xFFFFFFFFL));
      float mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
      if (!(mx < minX + margin || mx > maxX - margin || my < minY + margin || my > maxY - margin)) interior++;
    }
    return interior;
  }

  static void seams() {
    for (boolean dilate : new boolean[] {true, false}) {
      for (float voxel : new float[] {0.03f, 0.04f}) {
        Params p = TestScene.ideal(voxel);
        p.dilate = dilate;
        Fusion f = new Fusion(p);
        int n = interiorBoundaryEdges(collect(scan(f, Mat4.identity(), 5, null)), 0.13f);
        check(n == 0, "dilate=" + dilate + " voxel=" + voxel + ": " + n + " aristas de borde interiores");
      }
    }
  }

  static void rotatedCamera() {
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    Set3 s = collect(scan(f, pose(Math.PI / 2, 0.3, 1.2, -0.7), 5, null));
    check(s.used().size() > 300, "pocos vértices");
    for (int i : s.used()) check(Math.abs(s.verts.get(i)[0] - (0.3f - 1.5f)) < 0.02f, "x=" + s.verts.get(i)[0]);
  }

  static void colorOrientation() {
    DepthFrame frame = TestScene.plane(1.5f, Mat4.identity());
    int cw = 40, ch = 80;
    byte[] color = new byte[cw * ch * 3];
    for (int r = 0; r < ch; r++) for (int c = 0; c < cw; c++) {
      boolean top = r < ch / 2; // fila 0 = arriba de la imagen
      int o = (r * cw + c) * 3;
      color[o] = (byte) (top ? 255 : 0); color[o + 1] = (byte) (top ? 0 : 255); color[o + 2] = 0;
    }
    frame.withColor(color, cw, ch);
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    Set3 s = collect(scan(f, Mat4.identity(), 5, frame));
    int checked = 0;
    for (int i : s.used()) {
      float y = s.verts.get(i)[1];
      if (Math.abs(y) < 0.1f) continue;
      float[] c = s.cols.get(i);
      if (y > 0) check(c[0] > 0.9f && c[1] < 0.1f, "arriba debería ser rojo");
      else check(c[1] > 0.9f && c[0] < 0.1f, "abajo debería ser verde");
      checked++;
    }
    check(checked > 200, "pocos vértices revisados: " + checked);
  }

  static void weights() {
    Params p = Params.withVoxel(0.04f);
    check(p.depthWeight(0.8f) == 1 && p.depthWeight(1.5f) == 1, "peso cercano");
    check(Math.abs(p.depthWeight(2.25f) - 0.5f) < 1e-6, "peso a 2.25 m");
    check(p.depthWeight(3f) == 0 && p.depthWeight(3.5f) == 0, "peso lejano");
    Fusion far = new Fusion(p);
    for (int k = 0; k < 4; k++) far.integrateCpu(TestScene.plane(3.5f, Mat4.identity()));
    check(far.volume.size() == 0, "una pared a 3.5 m no debe integrarse");
    Fusion near = new Fusion(p);
    for (int k = 0; k < 4; k++) near.integrateCpu(TestScene.plane(1.5f, Mat4.identity()));
    check(near.volume.size() > 0, "una pared a 1.5 m sí");
    // confianza baja: no integra
    float[] conf = new float[TestScene.W * TestScene.H];
    java.util.Arrays.fill(conf, 0.05f);
    DepthFrame lowConf = new DepthFrame(TestScene.W, TestScene.H, TestScene.plane(1.5f, Mat4.identity()).depth, conf,
        TestScene.FX, TestScene.FY, TestScene.CX, TestScene.CY, Mat4.identity());
    Fusion lc = new Fusion(p);
    for (int k = 0; k < 4; k++) lc.integrateCpu(lowConf);
    check(lc.allMeshes().isEmpty(), "confianza baja no debe producir malla");
  }

  static void cleanup() {
    float[] depth = TestScene.plane(1.5f, Mat4.identity()).depth.clone();
    for (int j = 20; j < 32; j++) for (int i = 20; i < 32; i++) depth[j * TestScene.W + i] = 0.8f;
    DepthFrame frame = new DepthFrame(TestScene.W, TestScene.H, depth, null, TestScene.FX, TestScene.FY, TestScene.CX, TestScene.CY, Mat4.identity());
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    Set3 before = collect(scan(f, Mat4.identity(), 5, frame));
    int near = 0;
    for (int i : before.used()) if (before.verts.get(i)[2] > -1.2f) near++;
    check(near > 0, "el fragmento cercano debería existir antes de limpiar");
    Fusion.Final fin = f.finalMeshes(80, 0.004);
    Set3 after = collect(fin.meshes);
    int nearAfter = 0;
    for (int i : after.used()) if (after.verts.get(i)[2] > -1.2f) nearAfter++;
    check(nearAfter == 0, "el fragmento debería eliminarse");
    check(fin.removedTris > 0 && after.tris.size() > 500, "la pared debe conservarse");
  }

  static void volumeRoundTrip() throws Exception {
    Fusion a = new Fusion(TestScene.ideal(0.04f));
    TestScene.scanRoom(a);
    int ta = a.finalMeshes(80, 0.004).totalTris;
    Volume v = Volume.load(new java.io.ByteArrayInputStream(a.volume.export()), TestScene.ideal(0.04f), false);
    Fusion b = new Fusion(TestScene.ideal(0.04f));
    b.setVolume(v);
    int tb = b.finalMeshes(80, 0.004).totalTris;
    check(ta > 1000, "pocos triángulos " + ta);
    check(Math.abs(ta - tb) / (double) ta < 0.02, "triángulos " + ta + " vs " + tb);
  }

  static void freeze() throws Exception {
    Params p = TestScene.ideal(0.03f);
    Fusion saved = new Fusion(p);
    TestScene.scanRoom(saved);
    byte[] vol = saved.volume.export();
    Fusion ref = new Fusion(p);
    ref.setVolume(Volume.load(new java.io.ByteArrayInputStream(vol), p, true));
    HashSet<String> orig = new HashSet<>();
    for (MeshPart m : ref.allMeshes()) for (int i = 0; i < m.positions.length; i += 3) orig.add(Math.round(m.positions[i] * 1e4) + "," + Math.round(m.positions[i + 1] * 1e4) + "," + Math.round(m.positions[i + 2] * 1e4));
    double[] kept = new double[2];
    for (int mode = 0; mode < 2; mode++) {
      Fusion cont = new Fusion(p);
      cont.setVolume(Volume.load(new java.io.ByteArrayInputStream(vol), p, mode == 0));
      float[] Atrue = pose(0.1, 0.05, 0, -0.1);
      cont.align = pose(0.1, 0.10, 0, -0.1); // 5 cm de error en x
      float[] Ainv = Mat4.invertRigid(Atrue);
      for (double[] yp : new double[][] {{0, -0.3}, {0.4, -0.35}, {-0.4, -0.3}}) {
        float[] Mw = Mat4.pose(yp[0], yp[1], 0, 1.3, 0);
        for (int k = 0; k < 4; k++) cont.integrateCpu(TestScene.room(Mat4.mul(Ainv, Mw)).withColor(null, 0, 0));
      }
      HashSet<String> after = new HashSet<>();
      for (MeshPart m : cont.allMeshes()) for (int i = 0; i < m.positions.length; i += 3) after.add(Math.round(m.positions[i] * 1e4) + "," + Math.round(m.positions[i + 1] * 1e4) + "," + Math.round(m.positions[i + 2] * 1e4));
      int k = 0;
      for (String s : orig) if (after.contains(s)) k++;
      kept[mode] = k / (double) orig.size();
    }
    System.out.printf("     vértices originales intactos: congelado %.1f%%, mezclado %.1f%%%n", kept[0] * 100, kept[1] * 100);
    check(kept[0] > 0.97, "congelado debería conservar el original: " + kept[0]);
    check(kept[1] < kept[0] - 0.05, "el control (mezclado) debería alterar el original: " + kept[1]);
  }

  static void icp() {
    Fusion saved = new Fusion(TestScene.ideal(0.04f));
    TestScene.scanRoom(saved);
    float[] A = pose(0.25, 0.15, 0.03, -0.2), Ainv = Mat4.invertRigid(A);
    List<Float> pts = new ArrayList<>();
    for (double[] yp : new double[][] {{0.1, -0.35}, {-0.3, -0.3}, {0.5, -0.4}, {-0.7, -0.2}}) {
      float[] Mw = Mat4.pose(yp[0], yp[1], 0.05, 1.3, 0.1);
      DepthFrame f = TestScene.room(Mat4.mul(Ainv, Mw));
      DepthFrame fw = new DepthFrame(f.width, f.height, TestScene.room(Mw).depth, null, f.fx, f.fy, f.cx, f.cy, Mat4.mul(Ainv, Mw));
      for (float v : Fusion.samplePoints(fw, saved.params, 4)) pts.add(v);
    }
    float[] src = new float[pts.size()];
    for (int i = 0; i < src.length; i++) src[i] = pts.get(i);
    Fusion.Pose4 r = saved.alignPoints(src, new double[] {0.2, 0.1, 0, -0.15}, new float[] {0.25f, 0.15f, 0.08f, 0.05f}, 5);
    check(Mat4.angleDiff(r.yaw, 0.25) < 0.02, "yaw " + r.yaw);
    check(Math.hypot(Math.hypot(r.t[0] - 0.15, r.t[1] - 0.03), r.t[2] + 0.2) < 0.03, "t " + java.util.Arrays.toString(r.t));
  }

  static void relocalization() {
    Fusion saved = new Fusion(TestScene.ideal(0.03f));
    TestScene.scanRoom(saved);
    Cloud savedCloud = saved.surfaceCloud(true);
    long[] seed = {7};
    java.util.function.DoubleSupplier rnd = () -> (seed[0] = (seed[0] * 16807) % 2147483647) / 2147483647.0;
    int ok = 0, top3 = 0;
    long t0 = System.nanoTime();
    for (int k = 0; k < 20; k++) {
      double x, z;
      do { x = -1.1 + rnd.getAsDouble() * 2.7; z = -2.1 + rnd.getAsDouble() * 3.2; } while (x > 0 && x < 0.9 && z > -1.7 && z < -0.6);
      double yawTrue = rnd.getAsDouble() * 2 * Math.PI - Math.PI;
      float[] A = pose(yawTrue, (rnd.getAsDouble() - 0.5) * 2, (rnd.getAsDouble() - 0.5) * 0.3, (rnd.getAsDouble() - 0.5) * 2);
      float[] Ainv = Mat4.invertRigid(A);
      Fusion sess = new Fusion(TestScene.ideal(0.05f));
      sess.params.minWeight = 1;
      List<DepthFrame> frames = new ArrayList<>();
      int fi = 0;
      for (double yaw = 0; yaw < 2 * Math.PI; yaw += Math.PI / 8) {
        for (double pitch : new double[] {-0.35, 0}) {
          float[] Mw = Mat4.pose(yaw, pitch, x, 1.3, z);
          DepthFrame fw = TestScene.room(Mw);
          DepthFrame fs = new DepthFrame(fw.width, fw.height, fw.depth, null, fw.fx, fw.fy, fw.cx, fw.cy, Mat4.mul(Ainv, Mw));
          sess.integrateCpu(fs);
          if (fi++ % 2 == 0) frames.add(fs); // uno de cada dos frames para la prueba de visibilidad
        }
      }
      final Cloud sc = sess.surfaceCloud(true);
      Reloc.Result r = Reloc.relocalize(savedCloud, sc,
          init -> saved.alignPoints(sc.pos, init, new float[] {0.25f, 0.15f, 0.08f, 0.05f}, 4),
          (yaw, t) -> saved.scorePose(sc, yaw, t, Reloc.RES_TOL, Reloc.COS_TOL),
          frames, null, null, null, null, null);
      boolean good = false;
      for (int c = 0; c < Math.min(3, r.candidates.size()); c++) {
        Reloc.Candidate cd = r.candidates.get(c);
        boolean g = Mat4.angleDiff(cd.yaw, yawTrue) < Math.PI / 180 && Math.hypot(Math.hypot(cd.t[0] - A[12], cd.t[1] - A[13]), cd.t[2] - A[14]) < 0.03;
        if (g && c == 0 && r.ok) ok++;
        if (g) good = true;
      }
      if (good) top3++;
    }
    System.out.printf("     relocalización: %d/20 primero correcto, %d/20 entre los 3 primeros, %.0f ms por intento%n", ok, top3, (System.nanoTime() - t0) / 1e6 / 20);
    // 17 y no 18 como en la web: los 3 fallos son la simetría de 180° (la sesión ve 360° y el
    // escaneo ~160°, y la girada encaja la mitad no escaneada). La correcta queda 2.ª y la
    // coincidencia en vivo la distingue (test "coincidencia en vivo"). Con la nube en orden
    // determinista (Fusion.cloudFrom) el resultado es estable: 17/20.
    check(ok >= 17, "solo " + ok + "/20 correctos");
    check(top3 >= 19, "solo " + top3 + "/20 entre los 3 primeros");
  }

  static void anchors() {
    float[] A = pose(2.3, -0.9, 0.04, 1.6), Ainv = Mat4.invertRigid(A);
    float[][] saved = {{0.6f, 0.75f, -1.4f}, {-1.45f, 1.1f, -2.45f}, {1.95f, 0.3f, 0.2f}};
    long[] seed = {3};
    List<float[]> savedL = new ArrayList<>(), sess = new ArrayList<>();
    for (float[] p : saved) {
      float[] q = new float[3];
      Mat4.transform(Ainv, p[0], p[1], p[2], q);
      for (int a = 0; a < 3; a++) q[a] += ((seed[0] = (seed[0] * 16807) % 2147483647) / 2147483647.0 - 0.5) * 0.04;
      savedL.add(p); sess.add(q);
    }
    for (int n = 2; n <= 3; n++) {
      Reloc.AnchorSolution s = Reloc.solveAnchors(savedL.subList(0, n), sess.subList(0, n));
      check(Mat4.angleDiff(s.yaw, 2.3) < 1.5 * Math.PI / 180, n + " anclas: yaw");
      check(Math.hypot(Math.hypot(s.t[0] + 0.9, s.t[1] - 0.04), s.t[2] - 1.6) < 0.05, n + " anclas: t");
      check(s.maxResidual < 0.05f, "residuo " + s.maxResidual);
    }
    List<float[]> bad = new ArrayList<>(sess);
    bad.set(2, new float[] {sess.get(2)[0] + 0.4f, sess.get(2)[1], sess.get(2)[2]});
    check(Reloc.solveAnchors(savedL, bad).maxResidual > 0.15f, "el ancla mal marcada debería notarse");
    boolean threw = false;
    try { Reloc.solveAnchors(savedL.subList(0, 1), sess.subList(0, 1)); } catch (IllegalArgumentException e) { threw = true; }
    check(threw, "1 ancla debería fallar");
  }

  static void startGuess() {
    float[] A = pose(-1.9, 0.8, 0.05, -0.6), Ainv = Mat4.invertRigid(A);
    float[] P0 = Mat4.pose(0.15, -0.2, 0, 1.3, 0);
    Reloc.Candidate g = Reloc.startGuess(P0, Mat4.mul(Ainv, P0));
    check(Mat4.angleDiff(g.yaw, -1.9) < 1e-5, "yaw " + g.yaw);
    check(Math.abs(g.t[0] - 0.8) < 1e-4 && Math.abs(g.t[1] - 0.05) < 1e-4 && Math.abs(g.t[2] + 0.6) < 1e-4, "t");
  }

  static void live() {
    Fusion saved = new Fusion(TestScene.ideal(0.03f));
    TestScene.scanRoom(saved);
    Cloud cloud = saved.surfaceCloud(true);
    float[] A = pose(0.7, 0.4, 0, -0.3);
    float[] wrong = Mat4.mul(pose(Math.PI, 0.25, 0, -1.15), A);
    float[] Ainv = Mat4.invertRigid(A);
    double good = 0, bad = 0;
    for (double yaw : new double[] {-0.6, 0, 0.6}) {
      float[] Mw = Mat4.pose(yaw, -0.3, 0.1, 1.3, -0.2);
      DepthFrame fw = TestScene.room(Mw);
      DepthFrame fs = new DepthFrame(fw.width, fw.height, fw.depth, null, fw.fx, fw.fy, fw.cx, fw.cy, Mat4.mul(Ainv, Mw));
      int[] g = Reloc.liveAgreement(cloud, fs, A, Reloc.LIVE_TOL), b = Reloc.liveAgreement(cloud, fs, wrong, Reloc.LIVE_TOL);
      good += g[0] / (double) Math.max(1, g[0] + g[1]);
      bad += b[0] / (double) Math.max(1, b[0] + b[1]);
    }
    good /= 3; bad /= 3;
    System.out.printf("     coincidencia en vivo: correcta %.0f%%, girada 180° %.0f%%%n", good * 100, bad * 100);
    check(good > 0.8, "la correcta debería coincidir: " + good);
    check(bad < good - 0.3, "la girada debería coincidir menos: " + bad);
  }

  static void floor() {
    // nube con una "mesa" horizontal a 0.7 m y nada más abajo
    int n = 400;
    float[] pos = new float[n * 3], nor = new float[n * 3];
    for (int i = 0; i < n; i++) {
      pos[i * 3] = (i % 20) * 0.05f; pos[i * 3 + 1] = 0.7f; pos[i * 3 + 2] = -1 - (i / 20) * 0.05f;
      nor[i * 3 + 1] = 1;
    }
    Cloud c = new Cloud(pos, nor, n);
    check(Math.abs(Reloc.estimateFloor(c, null) - 0.7f) < 1e-4, "la estimación ingenua toma la mesa");
    Reloc.Result r = Reloc.relocalize(c, c, init -> new Fusion.Pose4(init[0], new float[] {(float) init[1], (float) init[2], (float) init[3]}, 1, 0),
        (yaw, t) -> 0.5, null, 0f, 0f, 1.25f, 1.25f, null);
    check(r.sessionFloor == 0f && r.savedFloor == 0f, "suelo " + r.sessionFloor + " / " + r.savedFloor);
  }

  static void collider() {
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    TestScene.scanRoom(f);
    Collider col = new Collider(f.finalMeshes(80, 0.004).meshes, 0);
    check(col.blocked(0.45f, -1.15f), "la caja debe bloquear");
    check(!col.blocked(0, 0), "el centro debe estar libre");
    float x = -0.6f, z = 0;
    for (int k = 0; k < 400; k++) { float[] r = col.move(x, z, 0, -0.01f); x = r[0]; z = r[1]; }
    check(z > -2.5f + 0.1f, "atravesó la pared: z=" + z);
    float x0 = x;
    for (int k = 0; k < 50; k++) { float[] r = col.move(x, z, 0.01f, -0.01f); x = r[0]; z = r[1]; }
    check(x > x0 + 0.3f, "debería deslizarse");
    float[] fr = col.nearestFree(0.45f, -1.15f, 3);
    check(!col.blocked(fr[0], fr[1]), "punto libre");
  }

  static void glb() {
    Fusion f = new Fusion(TestScene.ideal(0.04f));
    TestScene.scanRoom(f);
    List<MeshPart> meshes = f.finalMeshes(80, 0.004).meshes;
    byte[] data = Glb.write(meshes, "Prueba \"ñ\"");
    List<MeshPart> back = Glb.read(data);
    int va = 0, vb = 0, ia = 0, ib = 0;
    for (MeshPart m : meshes) { va += m.vertexCount(); ia += m.indices.length; }
    for (MeshPart m : back) { vb += m.vertexCount(); ib += m.indices.length; }
    check(va == vb && ia == ib, "vértices " + va + "/" + vb + " índices " + ia + "/" + ib);
    check(Math.abs(meshes.get(0).colors[0] - back.get(0).colors[0]) < 1e-6, "color");
    check(Math.abs(meshes.get(0).positions[4] - back.get(0).positions[4]) < 1e-6, "posición");
  }

  @SuppressWarnings("unchecked")
  static void json() {
    Object o = Json.parse("{\"a\":[1,2.5,-3e2],\"b\":\"x\\\"y\\u00f1\",\"c\":true,\"d\":null}");
    Map<String, Object> m = (Map<String, Object>) o;
    check(((List<Object>) m.get("a")).size() == 3, "array");
    check("x\"yñ".equals(m.get("b")), "string " + m.get("b"));
    check(Boolean.TRUE.equals(m.get("c")) && m.containsKey("d"), "bool/null");
    check(Json.quote("a\"b\n").equals("\"a\\\"b\\n\""), "quote");
  }

  static void frameLog() throws Exception {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = FrameLog.open(bos);
    List<DepthFrame> orig = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      DepthFrame f = TestScene.room(Mat4.pose(-0.6 + i * 0.25, -0.2, 0, 1.3, 0));
      f.timestampNs = i;
      orig.add(f);
      FrameLog.write(out, f);
    }
    out.flush();
    FrameLog.Reader r = new FrameLog.Reader(new java.io.ByteArrayInputStream(bos.toByteArray()));
    Fusion a = new Fusion(new Params()), b = new Fusion(new Params());
    int n = 0;
    DepthFrame g;
    while ((g = r.next()) != null) {
      DepthFrame f = orig.get(n++);
      check(g.timestampNs == f.timestampNs && g.width == f.width && (g.color != null) == (f.color != null), "cabecera");
      for (int i = 0; i < f.depth.length; i++) check(Math.abs(g.depth[i] - f.depth[i]) <= 0.0006f, "profundidad en mm");
      a.integrateCpu(f); b.integrateCpu(g);
    }
    check(n == orig.size(), "frames leídos " + n);
    int va = a.stats()[3], vb = b.stats()[3];
    a.allMeshes(); b.allMeshes();
    va = a.stats()[3]; vb = b.stats()[3];
    check(va > 1000 && Math.abs(va - vb) < va * 0.01, "vértices " + va + " vs " + vb);
  }

  static void metaShare() throws Exception {
    ScanMeta m = new ScanMeta();
    m.id = "20261127-1200"; m.name = "Salón \"norte\""; m.created = 1795000000123L; m.hasVolume = true;
    m.startPose = Mat4.pose(0.3, 0, 1, 1.4, 2); m.floorY = -1.35f;
    ScanMeta.Anchor a = new ScanMeta.Anchor();
    a.p = new float[] {1, 2, 3}; a.dist = 1.2f; a.camHeight = 1.4f; a.cam = new float[] {0, 1.4f, 0}; a.photo = "anchor0.jpg";
    m.anchors.add(a);
    ScanMeta r = ScanMeta.fromJson(m.toJson());
    check(r.name.equals(m.name) && r.created == m.created && r.hasVolume && !r.imported, "campos");
    check(Math.abs(r.floorY + 1.35f) < 1e-6 && r.startPose[13] == m.startPose[13], "pose y suelo");
    check(r.anchors.size() == 1 && r.anchors.get(0).p[2] == 3 && "anchor0.jpg".equals(r.anchors.get(0).photo), "anclas");
    byte[] bin = new byte[1000];
    for (int i = 0; i < bin.length; i++) bin[i] = (byte) (i * 37 + 11);
    for (int len = 997; len <= 1000; len++) {
      byte[] b = java.util.Arrays.copyOf(bin, len);
      check(java.util.Arrays.equals(b, ShareHtml.decode(ShareHtml.encode(b))), "base64 " + len);
      check(ShareHtml.encode(b).replace("\n", "").equals(java.util.Base64.getEncoder().encodeToString(b)), "base64 estándar " + len);
    }
    String html = ShareHtml.build("<title>{{TITLE}}</title><script id=\"glb\" type=\"application/octet-stream\">\n{{GLB}}\n</script>", bin, "a<b");
    check(html.contains("a&lt;b"), "escape");
    check(java.util.Arrays.equals(bin, ShareHtml.extractGlb(html)), "extraer glb");
  }
}
