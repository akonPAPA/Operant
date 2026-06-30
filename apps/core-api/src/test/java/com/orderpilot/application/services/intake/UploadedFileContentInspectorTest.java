package com.orderpilot.application.services.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class UploadedFileContentInspectorTest {

  private final UploadedFileContentInspector inspector = new UploadedFileContentInspector(false);

  @Test
  void acceptsValidPdfSignature() {
    byte[] pdf = "%PDF-1.4 minimal".getBytes(StandardCharsets.US_ASCII);
    assertThat(inspector.inspect(pdf, "application/pdf", "quote.pdf")).isEqualTo("application/pdf");
  }

  @Test
  void rejectsFakePdfWithPdfExtension() {
    byte[] bytes = new byte[] {0x4D, 0x5A, 0x00, 0x01};
    assertThatThrownBy(() -> inspector.inspect(bytes, "application/pdf", "evil.pdf"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsValidPngSignature() {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    assertThat(inspector.inspect(png, "image/png", "scan.png")).isEqualTo("image/png");
  }

  @Test
  void acceptsValidJpegSignature() {
    byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
    assertThat(inspector.inspect(jpeg, "image/jpeg", "photo.jpg")).isEqualTo("image/jpeg");
  }

  @Test
  void rejectsMismatchedExtension() {
    byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(() -> inspector.inspect(pdf, "application/pdf", "file.png"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extension");
  }

  @Test
  void rejectsEmptyPayload() {
    assertThatThrownBy(() -> inspector.inspect(new byte[0], "application/pdf", "empty.pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void rejectsBinaryRenamedAsText() {
    byte[] binary = new byte[] {0x00, 0x01, 0x02, 0x03};
    assertThatThrownBy(() -> inspector.inspect(binary, "text/plain", "notes.txt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsPlainTextFile() {
    byte[] text = "SKU,Qty\nABC,2\n".getBytes(StandardCharsets.UTF_8);
    assertThat(inspector.inspect(text, "text/csv", "lines.csv")).isEqualTo("text/csv");
  }

  @Test
  void acceptsMinimalOfficeOpenXmlZip() throws Exception {
    byte[] xlsx = minimalXlsxZip();
    assertThat(inspector.inspect(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "book.xlsx"))
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  }

  @Test
  void rejectsZipWithoutOfficeStructure() throws Exception {
    byte[] zip = zipWithEntry("readme.txt", "hello");
    assertThatThrownBy(() -> inspector.inspect(zip,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "fake.xlsx"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Spreadsheet");
  }

  @Test
  void rejectsZipWithTraversalEntry() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("../evil.xml"));
      zip.write("x".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    byte[] bytes = out.toByteArray();
    assertThatThrownBy(() -> inspector.inspect(bytes,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "bad.xlsx"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path");
  }

  @Test
  void rejectsZipWithTooManyEntries() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (int i = 0; i < UploadedFileContentInspector.MAX_ZIP_ENTRIES + 1; i++) {
        zip.putNextEntry(new ZipEntry("xl/sheet" + i + ".xml"));
        zip.write("x".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertThatThrownBy(() -> inspector.inspect(out.toByteArray(),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "many.xlsx"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too many entries");
  }

  @Test
  void rejectsLegacyXlsByDefault() {
    byte[] ole = new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00};
    assertThatThrownBy(() -> inspector.inspect(ole, "application/vnd.ms-excel", "legacy.xls"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Legacy spreadsheet");
  }

  @Test
  void errorMessagesDoNotEchoFileBytes() {
    byte[] secret = "TOP-SECRET-SKU-LIST".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> inspector.inspect(secret, "application/pdf", "secret.pdf"))
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("TOP-SECRET"));
  }

  private static byte[] minimalXlsxZip() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
      zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      zip.write("<worksheet/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private static byte[] zipWithEntry(String name, String content) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }
}
