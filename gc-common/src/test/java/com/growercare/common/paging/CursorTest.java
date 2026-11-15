package com.growercare.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.common.error.ValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-CASE-02 R31/R32: opaque keyset cursors. */
class CursorTest {

    @ParameterizedTest(name = "CASE-R31 round trip {0}")
    @ValueSource(longs = {1L, 42L, 7_398_046_511_104L, Long.MAX_VALUE})
    void roundTripsAndIsOpaque(long id) {
        var cursor = Cursor.encode(id);
        assertThat(cursor).doesNotContain(Long.toString(id));
        assertThat(Cursor.decode(cursor)).isEqualTo(id);
    }

    @ParameterizedTest(name = "CASE-R32 invalid cursor \"{0}\"")
    @ValueSource(strings = {"%%%", "abc", "djE6", "djE6LTE", "djI6NDI", ""})
    void invalidCursorsAreValidationErrors(String cursor) {
        assertThatThrownBy(() -> Cursor.decode(cursor))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errors().getFirst().field()).isEqualTo("cursor"));
    }
}
