package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import redis.clients.jedis.Connection;

class RedisConsumerPackagingContractTest {
    @Test void standaloneRedisConsumerRetainsJedisRequiredLoggingApiWithoutTestClasspathHelp() throws Exception {
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Document published;
        try (InputStream input = Files.newInputStream(module.resolve("pom.xml"))) {
            published = parse(input);
        }
        assertEquals("cachedb-storage-redis", text(published.getDocumentElement(), "artifactId"));
        assertTrue(exported(dependency(published, "redis.clients", "jedis")),
                "Standalone consumers require the production Jedis dependency");

        // Inspect Jedis's own metadata, never LoggerFactory's presence on the test classpath.
        Path jedisJar = Path.of(Connection.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(jedisJar.toFile())) {
            var pom = jar.getJarEntry("META-INF/maven/redis.clients/jedis/pom.xml");
            assertNotNull(pom, "Cannot verify the selected Jedis artifact without its published POM");
            try (InputStream input = jar.getInputStream(pom)) {
                assertTrue(exported(dependency(parse(input), "org.slf4j", "slf4j-api")),
                        "Review this contract if Jedis changes its mandatory logging dependency");
            }
        }
        assertTrue(loggingApiExported(published),
                "Jedis requires slf4j-api at runtime; test/starter dependencies must not supply it for consumers");

        for (String[] exclusion : List.of(new String[]{"org.slf4j", "slf4j-api"},
                new String[]{"org.slf4j", "*"}, new String[]{"*", "slf4j-api"}, new String[]{"*", "*"})) {
            Document denied = (Document) published.cloneNode(true);
            Element dependencies = child(denied.getDocumentElement(), "dependencies");
            Element directApi = dependency(denied, "org.slf4j", "slf4j-api");
            if (directApi != null) dependencies.removeChild(directApi);
            Element jedis = dependency(denied, "redis.clients", "jedis");
            Element exclusions = child(jedis, "exclusions");
            if (exclusions == null) exclusions = append(jedis, "exclusions", null);
            Element rule = append(exclusions, "exclusion", null);
            append(rule, "groupId", exclusion[0]);
            append(rule, "artifactId", exclusion[1]);
            assertFalse(loggingApiExported(denied), "Excluded logging API must break the consumer contract");

            Element api = append(dependencies, "dependency", null);
            append(api, "groupId", "org.slf4j");
            append(api, "artifactId", "slf4j-api");
            Element scope = append(api, "scope", "test");
            assertFalse(loggingApiExported(denied), "Test dependencies must not mask a missing runtime API");
            scope.setTextContent("provided");
            assertFalse(loggingApiExported(denied), "Provided dependencies are not exported to consumers");
            scope.setTextContent("runtime");
            Element optional = append(api, "optional", "true");
            assertFalse(loggingApiExported(denied), "Optional dependencies are not exported to consumers");
            optional.setTextContent("false");
            assertTrue(loggingApiExported(denied), "An explicit mandatory runtime API is a valid alternative");
            scope.setTextContent("compile");
            assertTrue(loggingApiExported(denied), "An explicit mandatory compile API is also valid");
        }
    }

    private static boolean loggingApiExported(Document pom) {
        if (exported(dependency(pom, "org.slf4j", "slf4j-api"))) return true;
        Element jedis = dependency(pom, "redis.clients", "jedis");
        if (!exported(jedis)) return false;
        Element exclusions = child(jedis, "exclusions");
        if (exclusions != null) {
            for (Node node = exclusions.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element rule && "exclusion".equals(rule.getLocalName())
                        && matches(text(rule, "groupId"), "org.slf4j")
                        && matches(text(rule, "artifactId"), "slf4j-api")) return false;
            }
        }
        return true;
    }

    private static boolean exported(Element dependency) {
        return dependency != null && List.of("", "compile", "runtime").contains(text(dependency, "scope"))
                && List.of("", "false").contains(text(dependency, "optional"))
                && List.of("", "jar").contains(text(dependency, "type"))
                && text(dependency, "classifier").isEmpty();
    }

    private static Element dependency(Document pom, String group, String artifact) {
        Element dependencies = child(pom.getDocumentElement(), "dependencies");
        Element found = null;
        if (dependencies == null) return null;
        for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element candidate && "dependency".equals(candidate.getLocalName())
                    && group.equals(text(candidate, "groupId")) && artifact.equals(text(candidate, "artifactId"))) {
                assertNull(found, "Ambiguous duplicate dependency declaration: " + group + ":" + artifact);
                found = candidate;
            }
        }
        return found;
    }

    private static boolean matches(String pattern, String value) { return "*".equals(pattern) || value.equals(pattern); }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().strip();
    }

    private static Element append(Element parent, String name, String value) {
        Element element = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
        if (value != null) element.setTextContent(value);
        parent.appendChild(element);
        return element;
    }

    private static Document parse(InputStream input) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(input);
    }
}
