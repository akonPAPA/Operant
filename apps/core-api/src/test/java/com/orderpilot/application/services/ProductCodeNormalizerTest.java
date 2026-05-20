package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductCodeNormalizerTest {
  @Test
  void normalizesCommonSkuAliasAndOemSeparators() {
    assertThat(ProductCodeNormalizer.normalize(" ab-1209 ")).isEqualTo("AB1209");
    assertThat(ProductCodeNormalizer.normalize("AB-1209")).isEqualTo("AB1209");
    assertThat(ProductCodeNormalizer.normalize("17801-0H050")).isEqualTo("178010H050");
    assertThat(ProductCodeNormalizer.normalize(" 17801 / 0h050 ")).isEqualTo("178010H050");
  }
}
