package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RfqTextLineExtractorTest {
  @Test
  void extractsSkuQuantityUomAndLocationFromDemoRfqText() {
    var lines =
        RfqTextLineExtractor.extractSingleLine(
            "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.");

    assertThat(lines).hasSize(1);
    var line = lines.get(0);
    assertThat(line.rawText())
        .isEqualTo(
            "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.");
    assertThat(line.rawSku()).isEqualTo("PAD-OE-04465");
    assertThat(line.quantity()).isEqualByComparingTo("2");
    assertThat(line.uom()).isEqualTo("EA");
    assertThat(line.requestedLocation()).isEqualTo("Almaty");
  }

  @Test
  void preservesRawTextWhenSkuIsMissing() {
    var lines = RfqTextLineExtractor.extractSingleLine("Need brake pads for Toyota Camry 2018.");

    assertThat(lines).hasSize(1);
    var line = lines.get(0);
    assertThat(line.rawText()).isEqualTo("Need brake pads for Toyota Camry 2018.");
    assertThat(line.rawSku()).isNull();
    assertThat(line.quantity()).isEqualByComparingTo("1");
    assertThat(line.uom()).isEqualTo("EA");
  }

  @Test
  void doesNotTreatNormalWordsOrStandaloneYearAsSku() {
    var line =
        RfqTextLineExtractor.extractSingleLine(
                "Need brake pads for Toyota Camry 2018 in Almaty.")
            .get(0);

    assertThat(line.rawSku()).isNull();
  }

  @Test
  void supportsCyrillicUomAndLocationWithoutChangingSkuSemantics() {
    var line =
        RfqTextLineExtractor.extractSingleLine(
                "Нужно 2 шт PAD-OE-04465 для Toyota Camry 2018, Алматы.")
            .get(0);

    assertThat(line.rawSku()).isEqualTo("PAD-OE-04465");
    assertThat(line.quantity()).isEqualByComparingTo("2");
    assertThat(line.uom()).isEqualTo("EA");
    assertThat(line.requestedLocation()).isEqualTo("Алматы");
  }

  @Test
  void blankTextReturnsNoLines() {
    assertThat(RfqTextLineExtractor.extractSingleLine(" ")).isEmpty();
  }
}
