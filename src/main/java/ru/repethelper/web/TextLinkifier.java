package ru.repethelper.web;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("textLinkifier")
public class TextLinkifier {
    private static final Pattern LINK = Pattern.compile("(?i)(https?://[^\\s<>]+|www\\.[^\\s<>]+)");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}";

    public String linkify(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = LINK.matcher(text);
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            html.append(escapeWithBreaks(text.substring(cursor, matcher.start())));
            String visibleUrl = trimTrailingPunctuation(matcher.group());
            if (visibleUrl.isEmpty() || !isSafeHttpUrl(toHref(visibleUrl))) {
                html.append(escapeWithBreaks(matcher.group()));
            } else {
                String href = toHref(visibleUrl);
                html.append("<a href=\"").append(HtmlUtils.htmlEscape(href)).append("\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">")
                        .append(HtmlUtils.htmlEscape(visibleUrl)).append("</a>");
                html.append(escapeWithBreaks(matcher.group().substring(visibleUrl.length())));
            }
            cursor = matcher.end();
        }
        html.append(escapeWithBreaks(text.substring(cursor)));
        return html.toString();
    }

    private String toHref(String value) { return value.regionMatches(true, 0, "www.", 0, 4) ? "https://" + value : value; }
    private boolean isSafeHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }
    private String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }
    private String escapeWithBreaks(String value) { return HtmlUtils.htmlEscape(value).replace("\r\n", "\n").replace("\n", "<br>"); }
}
