package com.springairag.core.logging;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackProfileConfigurationTest {

    @Test
    void verificationProfileHasConsoleRootAppender() throws Exception {
        var resource = getClass().getClassLoader()
                .getResourceAsStream("logback-spring.xml");
        assertTrue(resource != null, "logback-spring.xml must be available");

        try (resource) {
            var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(resource);
            var profiles = document.getElementsByTagName("springProfile");
            boolean configured = false;
            for (int index = 0; index < profiles.getLength(); index++) {
                Element profile = (Element) profiles.item(index);
                boolean includesVerification = Arrays.stream(
                                profile.getAttribute("name").split(","))
                        .map(String::trim)
                        .anyMatch("verification"::equals);
                if (includesVerification
                        && profile.getElementsByTagName("root").getLength() > 0
                        && profile.getElementsByTagName("appender-ref").getLength() > 0) {
                    configured = true;
                    break;
                }
            }
            assertTrue(configured,
                    "verification profile must configure a root appender");
        }
    }
}
