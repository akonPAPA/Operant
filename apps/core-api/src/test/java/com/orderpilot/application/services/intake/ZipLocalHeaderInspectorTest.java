package com.orderpilot.application.services.intake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ZipLocalHeaderInspectorTest {

  @Test
  void rejectsCraftedZipHighCompressionRatioFromLocalHeaders() {
    byte[] zip = ZipTestFixtures.craftedOfficeZipWithHighCompressionRatio();
    assertThatThrownBy(
            () ->
                ZipLocalHeaderInspector.inspect(
                    zip,
                    UploadedFileContentInspector.MAX_ZIP_ENTRIES,
                    UploadedFileContentInspector.MAX_ZIP_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_COMPRESSION_RATIO,
                    (name, count) -> {}))
        .hasMessageContaining("compression ratio");
  }

  @Test
  void rejectsCraftedZipHugeDeclaredUncompressedSizeFromLocalHeaders() {
    byte[] zip = ZipTestFixtures.craftedOfficeZipWithHugeDeclaredUncompressedSize();
    assertThatThrownBy(
            () ->
                ZipLocalHeaderInspector.inspect(
                    zip,
                    UploadedFileContentInspector.MAX_ZIP_ENTRIES,
                    UploadedFileContentInspector.MAX_ZIP_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_COMPRESSION_RATIO,
                    (name, count) -> {}))
        .hasMessageContaining("uncompressed size exceeds limit");
  }
  
  @Test
  void rejectsCraftedZipDeclaredCompressedSizeExceedsAvailableBytes() {
    byte[] zip = ZipTestFixtures.craftedOfficeZipWithTruncatedCompressedPayload();
    assertThatThrownBy(
            () ->
                ZipLocalHeaderInspector.inspect(
                    zip,
                    UploadedFileContentInspector.MAX_ZIP_ENTRIES,
                    UploadedFileContentInspector.MAX_ZIP_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_SINGLE_ENTRY_UNCOMPRESSED_BYTES,
                    UploadedFileContentInspector.MAX_ZIP_COMPRESSION_RATIO,
                    (name, count) -> {}))
        .hasMessageContaining("Archive structure");
  }
}
