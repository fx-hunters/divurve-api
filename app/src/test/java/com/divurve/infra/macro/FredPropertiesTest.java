package com.divurve.infra.macro;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FredPropertiesTest {

    @Test
    void applies_default_base_url_when_null() {
        FredProperties p = new FredProperties(null, "KEY", false, "0 10 4 * * *");
        assertThat(p.baseUrl()).isEqualTo("https://api.stlouisfed.org/fred");
        assertThat(p.apiKey()).isEqualTo("KEY");
    }

    @Test
    void applies_default_base_url_when_blank() {
        FredProperties p = new FredProperties("", "KEY", false, "0 10 4 * * *");
        assertThat(p.baseUrl()).isEqualTo("https://api.stlouisfed.org/fred");
    }

    @Test
    void keeps_provided_base_url() {
        FredProperties p = new FredProperties("http://x", "KEY", false, "0 10 4 * * *");
        assertThat(p.baseUrl()).isEqualTo("http://x");
    }
}
