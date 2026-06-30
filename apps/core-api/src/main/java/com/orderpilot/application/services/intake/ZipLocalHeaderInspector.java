package com.orderpilot.application.services.intake;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Bounded walk of ZIP local file headers (no decompression). Used to enforce archive bomb limits using
 * declared sizes from local headers; Java {@link java.util.zip.ZipOutputStream} may omit reliable ratios.
 */
final class ZipLocalHeaderInspector {

  private ZipLocalHeaderInspector() {}

  static boolean hasExplicitLocalEntrySizes(byte[] bytes) {
    for (int pos = 0; pos + 30 <= bytes.length; pos++) {
      if (bytes[pos] == 'P' && bytes[pos + 1] == 'K' && readU16(bytes, pos + 2) == 0x0403) {
        long compressedSize = readU32(bytes, pos + 18) & 0xffffffffL;
        long uncompressedSize = readU32(bytes, pos + 22) & 0xffffffffL;
        if (compressedSize > 0 || uncompressedSize > 0) {
          return true;
        }
      }
    }
    return false;
  }

  static void inspect(
      byte[] bytes,
      int maxEntries,
      long maxTotalUncompressedBytes,
      long maxSingleEntryUncompressedBytes,
      int maxCompressionRatio,
      BiConsumer<String, Integer> onEntry)
      throws IllegalArgumentException {
    int entries = 0;
    long totalUncompressed = 0;
    int pos = 0;
    while (pos + 4 <= bytes.length) {
      if (bytes[pos] != 'P' || bytes[pos + 1] != 'K') {
        pos++;
        continue;
      }
      int sig = readU16(bytes, pos + 2);
      if (sig == 0x0605) {
        break;
      }
      if (sig != 0x0403) {
        pos++;
        continue;
      }
      if (pos + 30 > bytes.length) {
        throw new IllegalArgumentException("Archive structure is not allowed");
      }
      entries++;
      if (entries > maxEntries) {
        throw new IllegalArgumentException("Archive contains too many entries");
      }
      long compressedSize = readU32(bytes, pos + 18) & 0xffffffffL;
      long uncompressedSize = readU32(bytes, pos + 22) & 0xffffffffL;
      int nameLen = readU16(bytes, pos + 26);
      int extraLen = readU16(bytes, pos + 28);
      int headerEnd = pos + 30 + nameLen + extraLen;
      if (headerEnd > bytes.length) {
        throw new IllegalArgumentException("Archive structure is not allowed");
      }
      String name = new String(bytes, pos + 30, nameLen, StandardCharsets.UTF_8);
      validateEntryPath(name);
      if (name.toLowerCase().endsWith(".zip")) {
        throw new IllegalArgumentException("Nested archive entries are not allowed");
      }
      if (uncompressedSize == 0xffffffffL
          || compressedSize == 0xffffffffL
          || (compressedSize == 0 && uncompressedSize == 0)) {
        throw new IllegalArgumentException("Archive entry sizes are not allowed");
      }
      if (uncompressedSize > maxSingleEntryUncompressedBytes) {
        throw new IllegalArgumentException("Archive entry uncompressed size exceeds limit");
      }
      totalUncompressed += uncompressedSize;
      if (totalUncompressed > maxTotalUncompressedBytes) {
        throw new IllegalArgumentException("Archive uncompressed size exceeds limit");
      }
      if (compressedSize > 0 && uncompressedSize / compressedSize > maxCompressionRatio) {
        throw new IllegalArgumentException("Archive compression ratio is not allowed");
      }
      onEntry.accept(name, entries);
      long dataEnd = headerEnd + compressedSize;
      if (dataEnd > bytes.length) {
        long available = bytes.length - headerEnd;
        if (available < 0) {
          throw new IllegalArgumentException("Archive structure is not allowed");
        }
        if (compressedSize > available) {
          compressedSize = available;
          dataEnd = bytes.length;
        }
      }
      if (dataEnd > bytes.length) {
        throw new IllegalArgumentException("Archive structure is not allowed");
      }
      pos = (int) dataEnd;
    }
  }

  private static void validateEntryPath(String name) {
    if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/") || name.contains("\\")) {
      throw new IllegalArgumentException("Archive entry path is not allowed");
    }
  }

  private static int readU16(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  private static long readU32(byte[] bytes, int offset) {
    return (bytes[offset] & 0xffL)
        | ((bytes[offset + 1] & 0xffL) << 8)
        | ((bytes[offset + 2] & 0xffL) << 16)
        | ((bytes[offset + 3] & 0xffL) << 24);
  }
}
