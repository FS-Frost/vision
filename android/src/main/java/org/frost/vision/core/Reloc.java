package org.frost.vision.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Relocalización (continuar un escaneo), portada de scan/reloc.js:
 * - búsqueda gruesa-a-fina 3D de (yaw, x, z) contra un campo de proximidad de la superficie guardada;
 * - ICP local sobre las semillas; orden por calidad penalizada por visibilidad;
 * - anclas (solveAnchors), pista de inicio (startGuess), suelo verificado, coincidencia en vivo.
 * Tolerancias calibradas con datos reales del moto g54 (ver CLAUDE.md de la raíz).
 */
public final class Reloc {
  private Reloc() {}

  static final double DEG = Math.PI / 180;
  /** Altura mínima de la cámara sobre el suelo con el teléfono en la mano (m). */
  public static final float MIN_CAM_ABOVE_FLOOR = 0.8f;
  public static final float RES_TOL = 0.05f, COS_TOL = 0.5f, VIS_MARGIN = 0.25f, VIS_WEIGHT = 2f, MIN_QUALITY = 0.03f;
  public static final int MAX_SHOWN = 5;
  /** Coincidencia en vivo: tolerancia (ruido medido entre sesiones). */
  public static final float LIVE_TOL = 0.15f;

  /** Candidato de pose sesión -> guardado. */
  public static final class Candidate {
    public double yaw;
    public float[] t;
    public double quality, visibility, rank, seedScore;
    public String tag;
    public float[] matrix() { return Mat4.yaw(yaw, t[0], t[1], t[2]); }
  }

  public static final class Result {
    public boolean ok, ambiguous;
    public String reason;
    public List<Candidate> candidates = new ArrayList<>();
    public float savedFloor, sessionFloor;
  }

  /** Altura del suelo: percentil bajo de los puntos con normal hacia arriba. */
  public static float estimateFloor(Cloud c, Float fallback) {
    List<Float> ys = new ArrayList<>();
    for (int i = 0; i < c.n; i++) if (c.nor[i * 3 + 1] > 0.85f) ys.add(c.pos[i * 3 + 1]);
    if (ys.size() >= 30) {
      Collections.sort(ys);
      return ys.get((int) (ys.size() * 0.05));
    }
    if (fallback != null) return fallback;
    float m = Float.MAX_VALUE;
    for (int i = 0; i < c.n; i++) m = Math.min(m, c.pos[i * 3 + 1]);
    return m;
  }

  // ------------------------------------------------------------ búsqueda 3D

  static final class ScoreVolume {
    float[] o = new float[3], mn = new float[3], mx = new float[3];
    int[] dim = new int[3];
    float cell;
    float[] data;
  }

  static ScoreVolume scoreVolume(Cloud c, float cell, float sigma, float pad) {
    ScoreVolume v = new ScoreVolume();
    v.cell = cell;
    for (int a = 0; a < 3; a++) { v.mn[a] = Float.MAX_VALUE; v.mx[a] = -Float.MAX_VALUE; }
    for (int i = 0; i < c.n; i++) for (int a = 0; a < 3; a++) {
      v.mn[a] = Math.min(v.mn[a], c.pos[i * 3 + a]); v.mx[a] = Math.max(v.mx[a], c.pos[i * 3 + a]);
    }
    for (int a = 0; a < 3; a++) { v.o[a] = v.mn[a] - pad; v.dim[a] = (int) Math.ceil((v.mx[a] - v.mn[a] + 2 * pad) / cell) + 1; }
    v.data = new float[v.dim[0] * v.dim[1] * v.dim[2]];
    int r = (int) Math.ceil(2.5 * sigma / cell);
    float k2 = 1f / (2 * sigma * sigma);
    for (int i = 0; i < c.n; i++) {
      float px = c.pos[i * 3], py = c.pos[i * 3 + 1], pz = c.pos[i * 3 + 2];
      int gx = (int) Math.floor((px - v.o[0]) / cell), gy = (int) Math.floor((py - v.o[1]) / cell), gz = (int) Math.floor((pz - v.o[2]) / cell);
      for (int dz = -r; dz <= r; dz++) {
        int z = gz + dz;
        if (z < 0 || z >= v.dim[2]) continue;
        for (int dy = -r; dy <= r; dy++) {
          int y = gy + dy;
          if (y < 0 || y >= v.dim[1]) continue;
          for (int dx = -r; dx <= r; dx++) {
            int x = gx + dx;
            if (x < 0 || x >= v.dim[0]) continue;
            float ex = (x + 0.5f) * cell + v.o[0] - px, ey = (y + 0.5f) * cell + v.o[1] - py, ez = (z + 0.5f) * cell + v.o[2] - pz;
            float val = (float) Math.exp(-(ex * ex + ey * ey + ez * ez) * k2);
            int idx = (z * v.dim[1] + y) * v.dim[0] + x;
            if (val > v.data[idx]) v.data[idx] = val;
          }
        }
      }
    }
    return v;
  }

  static double score3D(ScoreVolume v, float[] q, float c, float s, float tx, float ty, float tz) {
    double sum = 0;
    for (int i = 0; i < q.length; i += 3) {
      float x = c * q[i] + s * q[i + 2] + tx, y = q[i + 1] + ty, z = -s * q[i] + c * q[i + 2] + tz;
      int gx = (int) Math.floor((x - v.o[0]) / v.cell), gy = (int) Math.floor((y - v.o[1]) / v.cell), gz = (int) Math.floor((z - v.o[2]) / v.cell);
      if (gx >= 0 && gy >= 0 && gz >= 0 && gx < v.dim[0] && gy < v.dim[1] && gz < v.dim[2]) sum += v.data[(gz * v.dim[1] + gy) * v.dim[0] + gx];
    }
    return sum;
  }

  static float[] subsample(Cloud c, int max) {
    int n = Math.min(c.n, max);
    double step = Math.max(1, (double) c.n / max);
    float[] out = new float[n * 3];
    for (int k = 0; k < n; k++) {
      int i = (int) Math.floor(k * step) * 3;
      out[k * 3] = c.pos[i]; out[k * 3 + 1] = c.pos[i + 1]; out[k * 3 + 2] = c.pos[i + 2];
    }
    return out;
  }

  static final class Seed { double yaw; float tx, ty, tz; double score; }

  static List<Seed> nms(List<Seed> list, float minDist, double minAng, int max) {
    List<Seed> kept = new ArrayList<>();
    for (Seed c : list) {
      boolean dup = false;
      for (Seed k : kept) if (Math.hypot(k.tx - c.tx, k.tz - c.tz) < minDist && Mat4.angleDiff(k.yaw, c.yaw) < minAng) { dup = true; break; }
      if (dup) continue;
      kept.add(c);
      if (kept.size() >= max) break;
    }
    return kept;
  }

  /** Búsqueda gruesa (5°, 20 cm) y fina (1,5°, 5 cm, altura ±6 cm) en 3D. */
  public static List<Seed> searchVolume(Cloud saved, Cloud session, float dy, int maxCandidates) {
    if (saved.n < 100 || session.n < 100) return new ArrayList<>();
    ScoreVolume coarse = scoreVolume(saved, 0.1f, 0.15f, 0.4f);
    float[] qc = subsample(session, 200);
    List<Seed> all = new ArrayList<>();
    for (double yaw = 0; yaw < 2 * Math.PI - 1e-9; yaw += 5 * DEG) {
      float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
      List<Seed> best = new ArrayList<>();
      for (float tx = coarse.mn[0] - 0.4f; tx <= coarse.mx[0] + 0.4f; tx += 0.2f) {
        for (float tz = coarse.mn[2] - 0.4f; tz <= coarse.mx[2] + 0.4f; tz += 0.2f) {
          double sc = score3D(coarse, qc, c, s, tx, dy, tz);
          if (best.size() < 3 || sc > best.get(best.size() - 1).score) {
            Seed sd = new Seed(); sd.yaw = yaw; sd.tx = tx; sd.ty = dy; sd.tz = tz; sd.score = sc;
            best.add(sd);
            best.sort((a, b) -> Double.compare(b.score, a.score));
            if (best.size() > 3) best.remove(3);
          }
        }
      }
      all.addAll(best);
    }
    all.sort((a, b) -> Double.compare(b.score, a.score));
    List<Seed> seeds = nms(all, 0.5f, 20 * DEG, maxCandidates + 2);

    ScoreVolume fine = scoreVolume(saved, 0.05f, 0.07f, 0.2f);
    float[] qf = subsample(session, 400);
    List<Seed> refined = new ArrayList<>();
    for (Seed sd : seeds) {
      Seed best = new Seed(); best.score = -1;
      for (double da = -6; da <= 6; da += 1.5) {
        double yaw = sd.yaw + da * DEG;
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        for (float ddy : new float[] {-0.06f, 0, 0.06f}) {
          for (float dx = -0.2f; dx <= 0.2001f; dx += 0.05f) {
            for (float dz = -0.2f; dz <= 0.2001f; dz += 0.05f) {
              double sc = score3D(fine, qf, c, s, sd.tx + dx, dy + ddy, sd.tz + dz);
              if (sc > best.score) { best.yaw = yaw; best.tx = sd.tx + dx; best.ty = dy + ddy; best.tz = sd.tz + dz; best.score = sc; }
            }
          }
        }
      }
      best.score /= qf.length / 3.0;
      refined.add(best);
    }
    refined.sort((a, b) -> Double.compare(b.score, a.score));
    return nms(refined, 0.3f, 8 * DEG, maxCandidates);
  }

  // ------------------------------------------------------------ visibilidad / coincidencia en vivo

  public interface Visitor { void visit(float dist, float measured); }

  /** Proyecta la superficie guardada (pose candidata sesión->guardado) en cada frame. */
  public static void forEachProjected(Cloud saved, List<DepthFrame> frames, float[] matrix, int maxPoints, Visitor cb) {
    float[] Ainv = Mat4.invertRigid(matrix);
    int step = Math.max(1, saved.n / maxPoints);
    for (DepthFrame f : frames) {
      float[] C = Mat4.invertRigid(f.pose);
      for (int i = 0; i < saved.n; i += step) {
        float x = saved.pos[i * 3], y = saved.pos[i * 3 + 1], z = saved.pos[i * 3 + 2];
        float sx = Ainv[0] * x + Ainv[4] * y + Ainv[8] * z + Ainv[12];
        float sy = Ainv[1] * x + Ainv[5] * y + Ainv[9] * z + Ainv[13];
        float sz = Ainv[2] * x + Ainv[6] * y + Ainv[10] * z + Ainv[14];
        float cx = C[0] * sx + C[4] * sy + C[8] * sz + C[12];
        float cy = C[1] * sx + C[5] * sy + C[9] * sz + C[13];
        float cz = C[2] * sx + C[6] * sy + C[10] * sz + C[14];
        float dist = -cz;
        if (dist < 0.3f || dist > 3.5f) continue;
        int iu = (int) Math.floor(f.fx * cx / dist + f.cx), iv = (int) Math.floor(f.cy - f.fy * cy / dist);
        if (iu < 0 || iv < 0 || iu >= f.width || iv >= f.height) continue;
        float m = f.depth[iv * f.width + iu];
        if (!(m > 0)) continue;
        cb.visit(dist, m);
      }
    }
  }

  /** Tasa de "ver a través" de la superficie guardada (violaciones / (violaciones + apoyos)). */
  public static double visibilityRate(Cloud saved, List<DepthFrame> frames, float[] matrix, float margin) {
    final int[] v = new int[2];
    forEachProjected(saved, frames, matrix, 2500, (dist, m) -> {
      if (m > dist + margin) v[0]++;
      else if (Math.abs(m - dist) <= margin) v[1]++;
    });
    return v[0] / (double) Math.max(1, v[0] + v[1]);
  }

  /** Coincidencia en vivo con un frame: {match, through}. */
  public static int[] liveAgreement(Cloud saved, DepthFrame frame, float[] matrix, float tol) {
    final int[] r = new int[2];
    List<DepthFrame> one = new ArrayList<>();
    one.add(frame);
    forEachProjected(saved, one, matrix, 2500, (dist, m) -> {
      if (Math.abs(m - dist) <= tol) r[0]++;
      else if (m > dist) r[1]++;
    });
    return r;
  }

  // ------------------------------------------------------------ relocalización completa

  public interface Refiner { Fusion.Pose4 refine(double[] init); }
  public interface Scorer { double score(double yaw, float[] t); }

  /** Búsqueda + ICP + orden. El usuario decide con la confirmación visual. */
  public static Result relocalize(Cloud saved, Cloud session, Refiner refine, Scorer scorer, List<DepthFrame> frames,
                                  Float sessionFloorFallback, Float savedFloorFallback, Float sessionCamY, Float savedCamY,
                                  List<Candidate> extraSeeds) {
    Result res = new Result();
    float sf = estimateFloor(saved, savedFloorFallback);
    if (savedCamY != null && savedFloorFallback != null && savedCamY - sf < MIN_CAM_ABOVE_FLOOR) sf = savedFloorFallback;
    float qf = estimateFloor(session, sessionFloorFallback);
    if (sessionCamY != null && sessionFloorFallback != null && sessionCamY - qf < MIN_CAM_ABOVE_FLOOR) qf = sessionFloorFallback;
    res.savedFloor = sf; res.sessionFloor = qf;
    float dy = sf - qf;
    List<Candidate> out = new ArrayList<>();
    List<Object[]> seeds = new ArrayList<>();
    if (extraSeeds != null) for (Candidate e : extraSeeds) seeds.add(new Object[] {new double[] {e.yaw, e.t[0], e.t[1], e.t[2]}, e.tag, 0.0});
    if (session.n >= 100) {
      for (Seed s : searchVolume(saved, session, dy, 8)) seeds.add(new Object[] {new double[] {s.yaw, s.tx, s.ty, s.tz}, null, s.score});
    } else if (seeds.isEmpty()) {
      res.reason = "poca geometría a la vista";
      return res;
    }
    for (Object[] sd : seeds) {
      Fusion.Pose4 r = refine.refine((double[]) sd[0]);
      double q = scorer.score(r.yaw, r.t);
      Candidate dup = null;
      for (Candidate o : out) if (Math.hypot(o.t[0] - r.t[0], o.t[2] - r.t[2]) < 0.1 && Mat4.angleDiff(o.yaw, r.yaw) < 3 * DEG) { dup = o; break; }
      if (dup != null) {
        if (q > dup.quality) { dup.yaw = r.yaw; dup.t = r.t; dup.quality = q; }
        if (sd[1] != null) dup.tag = (String) sd[1];
        continue;
      }
      Candidate c = new Candidate();
      c.yaw = r.yaw; c.t = r.t; c.quality = q; c.seedScore = (Double) sd[2]; c.tag = (String) sd[1];
      out.add(c);
    }
    for (Candidate c : out) {
      c.visibility = frames != null && !frames.isEmpty() ? visibilityRate(saved, frames, c.matrix(), VIS_MARGIN) : 0;
      c.rank = c.quality - VIS_WEIGHT * c.visibility;
    }
    out.sort((a, b) -> Double.compare(b.rank, a.rank));
    res.candidates = out;
    Candidate best = out.isEmpty() ? null : out.get(0), second = out.size() > 1 ? out.get(1) : null;
    res.ambiguous = best != null && second != null && second.rank >= 0.9 * best.rank
        && (Math.hypot(second.t[0] - best.t[0], second.t[2] - best.t[2]) > 0.3 || Mat4.angleDiff(second.yaw, best.yaw) > 15 * DEG);
    res.ok = best != null && best.quality >= MIN_QUALITY; // el rank solo ordena; decide el usuario
    if (best == null) res.reason = "sin candidatos";
    else if (!res.ok) res.reason = "coincidencia insuficiente";
    return res;
  }

  /** "La sesión empezó en el punto inicial del escaneo": sesión -> guardado como yaw + traslación. */
  public static Candidate startGuess(float[] savedStartPose, float[] sessionStartPose) {
    float[] A = Mat4.mul(savedStartPose, Mat4.invertRigid(sessionStartPose));
    Candidate c = new Candidate();
    c.yaw = Math.atan2(A[8], A[0]);
    c.t = new float[] {A[12], A[13], A[14]};
    c.tag = "punto inicial";
    return c;
  }

  /** Anclas: Procrustes 2D en el plano del suelo + altura por centroides. */
  public static final class AnchorSolution {
    public double yaw;
    public float[] t;
    public float[] residuals;
    public float maxResidual;
    public float[] matrix() { return Mat4.yaw(yaw, t[0], t[1], t[2]); }
  }

  public static AnchorSolution solveAnchors(List<float[]> saved, List<float[]> session) {
    int n = Math.min(saved.size(), session.size());
    if (n < 2) throw new IllegalArgumentException("hacen falta al menos 2 anclas");
    float[] cp = new float[3], cq = new float[3];
    for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) { cp[a] += saved.get(i)[a] / n; cq[a] += session.get(i)[a] / n; }
    double sDot = 0, sCross = 0;
    for (int i = 0; i < n; i++) {
      double px = saved.get(i)[0] - cp[0], pz = saved.get(i)[2] - cp[2];
      double qx = session.get(i)[0] - cq[0], qz = session.get(i)[2] - cq[2];
      sDot += px * qx + pz * qz;
      sCross += px * qz - pz * qx;
    }
    AnchorSolution s = new AnchorSolution();
    s.yaw = Math.atan2(sCross, sDot);
    float c = (float) Math.cos(s.yaw), sn = (float) Math.sin(s.yaw);
    s.t = new float[] {cp[0] - (c * cq[0] + sn * cq[2]), cp[1] - cq[1], cp[2] - (-sn * cq[0] + c * cq[2])};
    s.residuals = new float[n];
    for (int i = 0; i < n; i++) {
      float[] q = session.get(i), p = saved.get(i);
      float x = c * q[0] + sn * q[2] + s.t[0], y = q[1] + s.t[1], z = -sn * q[0] + c * q[2] + s.t[2];
      s.residuals[i] = (float) Math.sqrt((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) + (z - p[2]) * (z - p[2]));
      s.maxResidual = Math.max(s.maxResidual, s.residuals[i]);
    }
    return s;
  }
}
