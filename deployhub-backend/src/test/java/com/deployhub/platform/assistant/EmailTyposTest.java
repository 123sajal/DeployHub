package com.deployhub.platform.assistant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTyposTest {

    @ParameterizedTest
    @CsvSource({
            "sajal@gmial.com, sajal@gmail.com",
            "sajal@gmail.con, sajal@gmail.com",
            "sajal@gamil.com, sajal@gmail.com",
            "a@yaho.com, a@yahoo.com",
            "a@hotmial.com, a@hotmail.com",
            "a@outlok.com, a@outlook.com",
            "a@rediffmial.com, a@rediffmail.com"})
    void commonTyposGetASuggestion(String typed, String suggested) {
        assertThat(EmailTypos.suggest(typed)).contains(suggested);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@gmail.com", "a@iitd.ac.in", "a@gembaconnect.in", "a@example.com", "a@mail.com",
            "a@yahoo.co.in", "not-an-email"})
    void correctOrUnknownDomainsAreLeftAlone(String typed) {
        assertThat(EmailTypos.suggest(typed)).isEmpty();
    }
}
