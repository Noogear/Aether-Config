package com.aether.config;

import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.codec.TypeRef;
import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.diagnostic.DiagnosticEntry;
import com.aether.config.diagnostic.DiagnosticSeverity;
import com.aether.config.interpolate.InterpolationMode;
import com.aether.config.interpolate.NodeInterpolator;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.NodeComments;
import com.aether.config.node.ScalarStyle;
import com.aether.config.node.SourceSpan;
import com.aether.config.view.ConfigSection;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoreConfigTest {

    enum Mode {
        AUTO,
        STORAGE_UNIT
    }

    record RangeSpec(int x, int y, int z) {
    }

    @Test
    void codecsHandleGenericsDurationsEnumsAndOneOf() {
        CodecRegistry codecs = CodecRegistry.defaults();
        ConfigCodec<RangeSpec> rangeCodec = ConfigCodec.oneOf(
                val -> {
                    int r = val.requireInt();
                    return new RangeSpec(r, r, r);
                },
                val -> {
                    ConfigSection sec = val.asSection();
                    if (sec.isEmpty()) {
                        throw new ConfigDecodeException("expected range section");
                    }
                    return new RangeSpec(sec.getInt("x", 1), sec.getInt("y", 1), sec.getInt("z", 1));
                }
        );
        codecs.register(RangeSpec.class, rangeCodec);

        ConfigDiagnostics diag = new ConfigDiagnostics();
        ConfigSection root = ConfigSection.ofMap("test.yml", Map.of(
                "mode", "storage-unit",
                "timeout", "10s",
                "ticks", "20t",
                "scalar-range", 7,
                "box-range", Map.of("x", 3, "y", 64, "z", 3),
                "nested", Map.of("alpha", List.of("1", "2"), "beta", List.of(10)),
                "1.21.4", "supported"
        ), diag, codecs);

        assertEquals(Mode.STORAGE_UNIT, root.decode("mode", Mode.class, null));
        assertEquals(Duration.ofSeconds(10), root.getDuration("timeout", null));
        assertEquals(Duration.ofMillis(1000), root.getDuration("ticks", null));
        assertEquals(new RangeSpec(7, 7, 7), root.decode("scalar-range", RangeSpec.class, null));
        assertEquals(new RangeSpec(3, 64, 3), root.decode("box-range", RangeSpec.class, null));
        assertEquals("supported", root.valueExact("1.21.4").asString(null));
        assertEquals("supported", root.getString("1.21.4", null));

        Map<String, List<Integer>> nested = root.decode(
                "nested",
                new TypeRef<Map<String, List<Integer>>>() {},
                Map.of()
        );
        assertEquals(List.of(1, 2), nested.get("alpha"));
        assertEquals(List.of(10), nested.get("beta"));
        assertFalse(diag.hasErrors());
        assertFalse(diag.hasWarnings());

        Optional<BigInteger> emptyOpt = root.decode("missing", new TypeRef<Optional<BigInteger>>() {}, Optional.empty());
        assertTrue(emptyOpt.isEmpty());
    }

    @Test
    void unknownKeysEmitSpellingHintsAndFormattedSnippets() {
        ConfigDiagnostics diag = new ConfigDiagnostics();
        CodecRegistry codecs = CodecRegistry.defaults();

        SourceSpan span = new SourceSpan("config.yml", 12, 3, 18, "  suction-inteval: 10");
        ConfigNode.MapNode root = new ConfigNode.MapNode(
                Map.of(
                        "suction-inteval", new ConfigNode.MapEntry(
                                "suction-inteval",
                                ConfigNode.ScalarNode.of(10),
                                NodeComments.EMPTY,
                                span
                        )
                ),
                SourceSpan.ofSource("config.yml"),
                NodeComments.EMPTY
        );

        ConfigSection section = ConfigSection.ofNode("config.yml", "", root, diag, codecs);
        section.warnUnknownKeys(Set.of("suction-interval", "sell-interval"));

        assertTrue(diag.hasWarnings());
        DiagnosticEntry entry = diag.entries().get(0);
        assertEquals(DiagnosticSeverity.WARN, entry.severity());
        assertEquals(DiagnosticCategory.SCHEMA, entry.category());
        String formatted = entry.format();
        assertTrue(formatted.contains("config.yml:12:3"));
        assertTrue(formatted.contains("12 |   suction-inteval: 10"));
        assertTrue(formatted.contains("Did you mean 'suction-interval'?"));
    }

    @Test
    void nodeInterpolatorResolvesEnvRefsAndDetectsCyclesWithoutTouchingSingleBraces() {
        ConfigDiagnostics diag = new ConfigDiagnostics();
        NodeInterpolator interpolator = new NodeInterpolator(
                InterpolationMode.ALL,
                key -> "DB_HOST".equals(key) ? "db.internal" : null,
                key -> "app.port".equals(key) ? "5432" : null
        );

        ConfigDocument doc = ConfigDocument.empty("app.yml");
        doc.setRaw("host", "${env:DB_HOST:localhost}");
        doc.setRaw("port", "${sys:app.port:3306}");
        doc.setRaw("url", "jdbc:postgresql://${ref:host}:${ref:port}/aether");
        doc.setRaw("message", "Hello {player}, literal \\${DB_HOST}");
        doc.setRaw("cycleA", "${ref:cycleB}");
        doc.setRaw("cycleB", "${ref:cycleA}");

        ConfigNode.MapNode resolved = interpolator.interpolate(doc.toNode(), diag);
        ConfigSection view = ConfigSection.ofNode("app.yml", "", resolved, diag, CodecRegistry.defaults());

        assertEquals("db.internal", view.getString("host", null));
        assertEquals(5432, view.getInt("port", 0));
        assertEquals("jdbc:postgresql://db.internal:5432/aether", view.getString("url", null));
        assertEquals("Hello {player}, literal ${DB_HOST}", view.getString("message", null));
        assertTrue(diag.hasErrors());
        assertTrue(diag.entries().stream().anyMatch(e -> e.category() == DiagnosticCategory.INTERPOLATION));
    }

    @Test
    void configDocumentSupportsEscapedDotsAndPreservesComments() {
        ConfigDocument doc = ConfigDocument.empty("test.yml");
        doc.setRaw("versions.1\\.21\\.4.enabled", true);
        doc.setBlockComments("versions.1\\.21\\.4", List.of("Minecraft 1.21.4 support"));

        ConfigNode.MapNode node = doc.toNode();
        ConfigNode.MapNode versions = (ConfigNode.MapNode) node.child("versions");
        assertNotNull(versions);
        ConfigNode.MapEntry entry = versions.entry("1.21.4");
        assertNotNull(entry);
        assertEquals(List.of("Minecraft 1.21.4 support"), entry.keyComments().block());
        assertEquals(true, ((ConfigNode.MapNode) entry.value()).child("enabled").rawValue());
        assertEquals(ScalarStyle.PLAIN, ((ConfigNode.ScalarNode) ((ConfigNode.MapNode) entry.value()).child("enabled")).style());
    }
}
