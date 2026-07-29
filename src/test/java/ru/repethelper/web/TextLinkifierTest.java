package ru.repethelper.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextLinkifierTest {
    private final TextLinkifier linkifier = new TextLinkifier();

    @Test void createsSafeLinksAndPreservesLineBreaks() {
        String html = linkifier.linkify("Смотри https://example.com/a?x=1&y=2.\nИ www.zoom.us/test");

        assertThat(html).contains("href=\"https://example.com/a?x=1&amp;y=2\"")
                .contains("https://www.zoom.us/test")
                .contains("<br>");
    }

    @Test void escapesMarkupInsteadOfRenderingIt() {
        assertThat(linkifier.linkify("<script>alert(1)</script>"))
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test void preservesOrdinaryUnicodeCharacters() {
        assertThat(linkifier.linkify("Задачи 1–5")).isEqualTo("Задачи 1–5");
    }
}
