package com.orderpilot.application.services.intake;



import java.nio.charset.StandardCharsets;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;



/**

 * Content-signature and archive-bomb checks for inbound uploads. Claimed MIME type and filename

 * extension are not trusted without a matching detected signature.

 */

@Component

public class UploadedFileContentInspector {



  static final int TEXT_SNIFF_LIMIT = 8 * 1024;

  static final int MAX_ZIP_ENTRIES = 512;

  static final long MAX_ZIP_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L;

  static final long MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES = 25L * 1024L * 1024L;

  static final int MAX_ZIP_COMPRESSION_RATIO = 100;



  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private static final byte[] PNG_MAGIC = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  private static final byte[] JPEG_MAGIC = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  private static final byte[] OLE_MAGIC = new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,

      (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

  private static final byte[] ZIP_MAGIC = new byte[] {'P', 'K', 0x03, 0x04};



  private final boolean allowLegacyXls;



  public UploadedFileContentInspector(

      @Value("${orderpilot.intake.allow-legacy-xls:false}") boolean allowLegacyXls) {

    this.allowLegacyXls = allowLegacyXls;

  }



  /**

   * @return canonical content type to persist after successful inspection

   */

  public String inspect(byte[] bytes, String claimedContentType, String originalFilename) {

    if (bytes == null || bytes.length == 0) {

      throw new IllegalArgumentException("Uploaded file must not be empty");

    }

    DetectedKind kind = detectKind(bytes, originalFilename);

    if (kind == DetectedKind.OLE_XLS && !allowLegacyXls) {

      throw new IllegalArgumentException("Legacy spreadsheet uploads are not allowed");

    }

    String extension = extensionFromFilename(originalFilename);

    String canonical = canonicalContentType(kind);

    if (claimedContentType != null && !claimedContentType.isBlank()

        && !canonical.equalsIgnoreCase(claimedContentType.trim())) {

      throw new IllegalArgumentException("Content type does not match file signature");

    }

    if (extension != null && !extensionMatchesKind(extension, kind)) {

      throw new IllegalArgumentException("File extension does not match file signature");

    }

    if (kind == DetectedKind.XLSX_ZIP) {

      inspectOfficeOpenXmlZip(bytes);

    }

    return canonical;

  }



  private enum DetectedKind {

    PDF, PNG, JPEG, OLE_XLS, XLSX_ZIP, TEXT_PLAIN, TEXT_CSV, JSON

  }



  private DetectedKind detectKind(byte[] bytes, String originalFilename) {

    if (startsWith(bytes, PDF_MAGIC)) {

      return DetectedKind.PDF;

    }

    if (startsWith(bytes, PNG_MAGIC)) {

      return DetectedKind.PNG;

    }

    if (startsWith(bytes, JPEG_MAGIC)) {

      return DetectedKind.JPEG;

    }

    if (startsWith(bytes, OLE_MAGIC)) {

      return DetectedKind.OLE_XLS;

    }

    if (startsWith(bytes, ZIP_MAGIC)) {

      return DetectedKind.XLSX_ZIP;

    }

    if (looksLikeJson(bytes)) {

      return DetectedKind.JSON;

    }

    if (looksLikeText(bytes)) {

      String ext = extensionFromFilename(originalFilename);

      return ".csv".equals(ext) ? DetectedKind.TEXT_CSV : DetectedKind.TEXT_PLAIN;

    }

    throw new IllegalArgumentException("Unsupported or unrecognized file content");

  }



  private static String canonicalContentType(DetectedKind kind) {

    return switch (kind) {

      case PDF -> "application/pdf";

      case PNG -> "image/png";

      case JPEG -> "image/jpeg";

      case OLE_XLS -> "application/vnd.ms-excel";

      case XLSX_ZIP -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

      case TEXT_CSV -> "text/csv";

      case TEXT_PLAIN -> "text/plain";

      case JSON -> "application/json";

    };

  }



  private static boolean extensionMatchesKind(String extension, DetectedKind kind) {

    return switch (kind) {

      case PDF -> ".pdf".equals(extension);

      case PNG -> ".png".equals(extension);

      case JPEG -> ".jpg".equals(extension) || ".jpeg".equals(extension);

      case OLE_XLS -> ".xls".equals(extension);

      case XLSX_ZIP -> ".xlsx".equals(extension);

      case TEXT_CSV -> ".csv".equals(extension);

      case TEXT_PLAIN -> ".txt".equals(extension);

      case JSON -> ".json".equals(extension);

    };

  }



  private static String extensionFromFilename(String originalFilename) {

    if (originalFilename == null || originalFilename.isBlank()) {

      return null;

    }

    String lower = originalFilename.toLowerCase(Locale.ROOT);

    int dot = lower.lastIndexOf('.');

    if (dot < 0 || dot == lower.length() - 1) {

      return null;

    }

    return lower.substring(dot);

  }



  private void inspectOfficeOpenXmlZip(byte[] bytes) {

    OfficeZipMarkers markers = new OfficeZipMarkers();

    if (ZipLocalHeaderInspector.hasExplicitLocalEntrySizes(bytes)) {

      inspectOfficeZipWithLocalHeaders(bytes, markers);

      if (!markers.isValidOfficeOpenXml()) {

        throw new IllegalArgumentException("Spreadsheet archive structure is not allowed");

      }

      return;

    }

    inspectOfficeZipWithZipInputStream(bytes, markers);

    if (!markers.isValidOfficeOpenXml()) {

      throw new IllegalArgumentException("Spreadsheet archive structure is not allowed");

    }

  }



  private void inspectOfficeZipWithLocalHeaders(byte[] bytes, OfficeZipMarkers markers) {

    ZipLocalHeaderInspector.inspect(

        bytes,

        MAX_ZIP_ENTRIES,

        MAX_ZIP_UNCOMPRESSED_BYTES,

        MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES,

        MAX_ZIP_COMPRESSION_RATIO,

        (name, ignored) -> markers.noteEntry(name));

  }



  private void inspectOfficeZipWithZipInputStream(byte[] bytes, OfficeZipMarkers markers) {

    int entries = 0;

    long totalUncompressed = 0;

    try (java.util.zip.ZipInputStream zip =

        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {

      java.util.zip.ZipEntry entry;

      while ((entry = zip.getNextEntry()) != null) {

        entries++;

        if (entries > MAX_ZIP_ENTRIES) {

          throw new IllegalArgumentException("Archive contains too many entries");

        }

        String name = entry.getName();

        if (name == null || name.contains("..") || name.startsWith("/") || name.contains("\\")) {

          throw new IllegalArgumentException("Archive entry path is not allowed");

        }

        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {

          throw new IllegalArgumentException("Nested archive entries are not allowed");

        }

        long uncompressed = entry.getSize();

        long compressed = entry.getCompressedSize();

        if (uncompressed >= 0) {

          if (uncompressed > MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES) {

            throw new IllegalArgumentException("Archive entry uncompressed size exceeds limit");

          }

          totalUncompressed += uncompressed;

          if (totalUncompressed > MAX_ZIP_UNCOMPRESSED_BYTES) {

            throw new IllegalArgumentException("Archive uncompressed size exceeds limit");

          }

          if (compressed > 0 && uncompressed / compressed > MAX_ZIP_COMPRESSION_RATIO) {

            throw new IllegalArgumentException("Archive compression ratio is not allowed");

          }

        }

        markers.noteEntry(name);

        zip.closeEntry();

      }

    } catch (java.io.IOException ex) {

      throw new IllegalArgumentException("Unable to inspect archive content");

    }

  }



  private static final class OfficeZipMarkers {

    private boolean hasContentTypes;

    private boolean hasWorkbook;

    private boolean hasWorksheet;



    void noteEntry(String name) {

      if ("[Content_Types].xml".equals(name)) {

        hasContentTypes = true;

      }

      if ("xl/workbook.xml".equals(name)) {

        hasWorkbook = true;

      }

      if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {

        hasWorksheet = true;

      }

    }



    boolean isValidOfficeOpenXml() {

      return hasContentTypes && hasWorkbook && hasWorksheet;

    }

  }



  private static boolean looksLikeJson(byte[] bytes) {

    int limit = Math.min(bytes.length, TEXT_SNIFF_LIMIT);

    int i = 0;

    while (i < limit && Character.isWhitespace(bytes[i])) {

      i++;

    }

    if (i >= limit) {

      return false;

    }

    char first = (char) (bytes[i] & 0xFF);

    return first == '{' || first == '[';

  }



  private static boolean looksLikeText(byte[] bytes) {

    int limit = Math.min(bytes.length, TEXT_SNIFF_LIMIT);

    for (int i = 0; i < limit; i++) {

      byte b = bytes[i];

      if (b == 0) {

        return false;

      }

      if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) {

        return false;

      }

    }

    return true;

  }



  private static boolean startsWith(byte[] bytes, byte[] prefix) {

    if (bytes.length < prefix.length) {

      return false;

    }

    for (int i = 0; i < prefix.length; i++) {

      if (bytes[i] != prefix[i]) {

        return false;

      }

    }

    return true;

  }

}


