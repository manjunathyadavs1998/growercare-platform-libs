package com.growercare.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaseNumberTest {

    @Test
    void dammMatchesPublishedVectorAndDesignDocExample() {
        assertThat(Damm.checkDigit("572")).isEqualTo(4);                 // reference vector
        assertThat(Damm.isValid("5724")).isTrue();
        assertThat(Damm.checkDigit("71031827482139")).isEqualTo(6);      // doc 04 example 71-0318-27-482139-6
    }

    @Test
    void parsesGroupedAndCompactForms() {
        var grouped = CaseNumber.parse("71-0318-27-482139-6").orElseThrow();
        var compact = CaseNumber.parse("710318274821396").orElseThrow();
        assertThat(grouped).isEqualTo(compact);
        assertThat(grouped.caseNoCc()).isEqualTo(71);
        assertThat(grouped.dayNumber()).isEqualTo(318);
        assertThat(grouped.shardNo()).isEqualTo(27);
        assertThat(grouped.counter()).isEqualTo(482139);
        assertThat(grouped.formatted()).isEqualTo("71-0318-27-482139-6");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "71-0318-27-482139-5",   // wrong check digit
            "71-0318-27-428139-6",   // adjacent transposition
            "01-0318-27-482139-6",   // leading zero
            "71031827482139",        // too short
            "7103182748213a6",       // non-digit
            ""})
    void rejectsTyposAndMalformedInput(String input) {
        assertThat(CaseNumber.parse(input)).isEmpty();
    }

    @Test
    void constructorRejectsInvalidDigits() {
        assertThatThrownBy(() -> new CaseNumber("710318274821395")).isInstanceOf(IllegalArgumentException.class);
    }
}
