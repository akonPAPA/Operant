package com.orderpilot.application.services.intake;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Crafted ZIP bytes for security tests (local header declared sizes). */
final class ZipTestFixtures {

  private ZipTestFixtures() {}

  static byte[] craftedOfficeZipWithHighCompressionRatio() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeLocalEntry(out, "xl/workbook.xml", 10_000_000L, 1L, new byte[] {'a'});
    writeEndOfCentralDirectory(out, 1);
    return out.toByteArray();
  }

  static byte[] craftedOfficeZipWithHugeDeclaredUncompressedSize() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeLocalEntry(out, "[Content_Types].xml", 50_000_000L, 1L, new byte[] {'x'});
    writeEndOfCentralDirectory(out, 1);
    return out.toByteArray();
  }
  
  static byte[] craftedOfficeZipWithTruncatedCompressedPayload() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeLocalEntry(out, "[Content_Types].xml", 100L, 100L, new byte[] {'x'});
    writeEndOfCentralDirectory(out, 1);
    return out.toByteArray();
  }

  private static void writeLocalEntry(
      ByteArrayOutputStream out, String name, long uncompressedSize, long compressedSize, byte[] data) {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    long declaredCompressed = compressedSize;
    out.writeBytes(new byte[] {'P', 'K', 0x03, 0x04});
    writeU16(out, 20);
    writeU16(out, 0);
    writeU16(out, 0);
    writeU16(out, 0);
    writeU16(out, 0);
    writeU32(out, 0);
    writeU32(out, declaredCompressed);
    writeU32(out, uncompressedSize);
    writeU16(out, nameBytes.length);
    writeU16(out, 0);
    out.writeBytes(nameBytes);
    out.writeBytes(data);
  }

  private static void writeEndOfCentralDirectory(ByteArrayOutputStream out, int entryCount) {
    out.writeBytes(new byte[] {'P', 'K', 0x05, 0x06});
    writeU16(out, 0);
    writeU16(out, 0);
    writeU16(out, entryCount);
    writeU16(out, entryCount);
    writeU32(out, 0);
    writeU32(out, 0);
    writeU16(out, 0);
  }

  private static void writeU16(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >> 8) & 0xff);
  }

  private static void writeU32(ByteArrayOutputStream out, long value) {
    out.write((int) (value & 0xff));
    out.write((int) ((value >> 8) & 0xff));
    out.write((int) ((value >> 16) & 0xff));
    out.write((int) ((value >> 24) & 0xff));
  }
}
