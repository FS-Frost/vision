package org.frost.vision.core;

/** Conjunto de long con direccionamiento abierto (sin boxing): para claves de bloque por frame. */
public final class LongSet {
  private static final long EMPTY = Long.MIN_VALUE;
  private long[] table;
  private int size, mask;

  public LongSet(int expected) {
    int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
    table = new long[cap];
    java.util.Arrays.fill(table, EMPTY);
    mask = cap - 1;
  }

  private static int mix(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    return (int) k;
  }

  public boolean add(long k) {
    int i = mix(k) & mask;
    while (true) {
      long v = table[i];
      if (v == EMPTY) {
        table[i] = k;
        if (++size * 2 > table.length) grow();
        return true;
      }
      if (v == k) return false;
      i = (i + 1) & mask;
    }
  }

  private void grow() {
    long[] old = table;
    table = new long[old.length * 2];
    java.util.Arrays.fill(table, EMPTY);
    mask = table.length - 1;
    size = 0;
    for (long v : old) if (v != EMPTY) add(v);
  }

  public int size() { return size; }

  public long[] toArray() {
    long[] out = new long[size];
    int n = 0;
    for (long v : table) if (v != EMPTY) out[n++] = v;
    return out;
  }
}
