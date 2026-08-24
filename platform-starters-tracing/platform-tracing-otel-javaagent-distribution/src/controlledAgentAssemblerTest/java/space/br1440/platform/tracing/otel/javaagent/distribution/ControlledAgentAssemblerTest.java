package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentAssemblerTest {

    private static final String ACP_PIN_RESOURCE =
            "META-INF/platform-tracing/upstream-acp-pin.properties";
    private static final String UPSTREAM_ACP_DESCRIPTOR =
            "inst/META-INF/services/"
                    + "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";

    @TempDir
    Path temporaryDirectory;

    @Test
    void wrong_reviewed_upstream_hash_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("wrong-upstream-hash");
        String actualHash = sha256Hex(Files.readAllBytes(fixture.upstream()));
        replaceExtensionPinText(
                fixture,
                "upstream.sha256=" + actualHash,
                "upstream.sha256=" + "0".repeat(64));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("does not match reviewed");
    }

    @Test
    void overflowing_acp_format_version_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("overflowing-acp-format-version");
        replaceExtensionPinText(
                fixture,
                "format.version=1",
                "format.version=4294967297");

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("outside the supported integer range");
    }

    @Test
    void non_ascii_acp_format_digit_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("non-ascii-acp-format-digit");
        replaceExtensionPinText(
                fixture,
                "format.version=1",
                "format.version=" + Character.toString(0x0661));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("format.version must contain ASCII digits only");
    }

    @Test
    void acp_resource_keys_are_strict() throws IOException {
        // given
        Fixture fixture = prepareFixture("acp-resource-keys");
        replaceExtensionPinText(fixture, "provider.0=", "unexpected.0=");

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("ACP pin resource contains unexpected key");
    }

    @Test
    void acp_provider_indexes_must_be_contiguous() throws IOException {
        // given
        Fixture fixture = prepareFixture("acp-provider-indexes");
        replaceExtensionPinText(fixture, "provider.4=", "provider.5=");

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("provider indexes must be contiguous from zero");
    }

    @Test
    void acp_providers_must_be_sorted_and_unique() throws IOException {
        // given
        Fixture fixture = prepareFixture("acp-provider-order");
        String first = "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer";
        String second = "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal."
                + "ResourceProviderPropertiesCustomizer";
        replaceExtensionPinText(fixture, first, "provider-order-placeholder");
        replaceExtensionPinText(fixture, second, first);
        replaceExtensionPinText(fixture, "provider-order-placeholder", second);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("providers must be sorted and unique");
    }

    @Test
    void upstream_acp_descriptor_must_match_reviewed_resource() throws IOException {
        // given
        Fixture fixture = prepareFixture("acp-provider-mismatch");
        replaceExtensionPinText(
                fixture,
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                "example.UnreviewedProvider");

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("does not match ACP pin resource");
    }

    @Test
    void dollar_is_accepted_in_acp_java_binary_names() throws IOException {
        // given
        Fixture fixture = prepareFixture("acp-provider-dollar");
        String originalProvider =
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer";
        String nestedProvider = "example.Outer$Provider";
        replaceExtensionPinText(fixture, originalProvider, nestedProvider);
        String descriptor = new String(
                readArchiveEntry(fixture.upstream(), UPSTREAM_ACP_DESCRIPTOR), StandardCharsets.UTF_8);
        Path rewritten = fixture.root().resolve("upstream-acp-rewritten.jar");
        rewriteArchive(
                fixture.upstream(),
                rewritten,
                Map.of(
                        UPSTREAM_ACP_DESCRIPTOR,
                        descriptor.replace(originalProvider, nestedProvider)
                                .getBytes(StandardCharsets.UTF_8)),
                Set.of(UPSTREAM_ACP_DESCRIPTOR));
        Files.move(rewritten, fixture.upstream(), StandardCopyOption.REPLACE_EXISTING);
        updateReviewedUpstreamHash(fixture);

        // when
        run(fixture);

        // then
        assertThat(new String(
                readArchiveEntry(fixture.packagedExtension(), ACP_PIN_RESOURCE),
                StandardCharsets.UTF_8))
                .contains("provider.0=" + nestedProvider);
    }

    @Test
    void oversized_acp_resource_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("oversized-acp-resource");
        byte[] oversized = new byte[16_385];
        new Random(1L).nextBytes(oversized);
        replaceExtensionPin(fixture, oversized);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput())
                .contains("ACP pin resource size is outside 1..16384 bytes");
    }

    @Test
    void extension_signature_material_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("signature-material");
        Path mutated = fixture.root().resolve("extension-mutated.jar");
        rewriteArchive(
                fixture.extension(),
                mutated,
                Map.of("META-INF/UNTRUSTED.SF", "untrusted".getBytes(StandardCharsets.UTF_8)),
                Set.of());
        Files.move(mutated, fixture.extension(), StandardCopyOption.REPLACE_EXISTING);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("contains signature material");
    }

    @Test
    void owned_resource_case_aliases_fail_in_library_and_runtime_dependency() throws IOException {
        // given
        Fixture libraryFixture = prepareFixture("library-owned-resource-case-alias");
        Path mutatedLibrary = libraryFixture.root().resolve("extension-mutated.jar");
        rewriteArchive(
                libraryFixture.extension(),
                mutatedLibrary,
                Map.of("Platform-Log-Event.Schema.Json", new byte[] {1}),
                Set.of());
        Files.move(
                mutatedLibrary,
                libraryFixture.extension(),
                StandardCopyOption.REPLACE_EXISTING);

        Fixture dependencyFixture = prepareFixture("dependency-owned-resource-case-alias");
        addRuntimeDependency(
                dependencyFixture,
                "owned-resource-alias.jar",
                List.of(syntheticEntry(
                        "META-INF/Platform-Tracing/Agent-Logging-Schema.Properties",
                        new byte[] {1})));

        // when
        BuildResult libraryResult = runAndFail(libraryFixture);
        BuildResult dependencyResult = runAndFail(dependencyFixture);

        // then
        assertThat(libraryResult.getOutput())
                .contains("contains case-aliased owned extension resource Platform-Log-Event.Schema.Json")
                .contains("canonical path is platform-log-event.schema.json");
        assertThat(dependencyResult.getOutput())
                .contains("contains case-aliased owned extension resource "
                        + "META-INF/Platform-Tracing/Agent-Logging-Schema.Properties")
                .contains("canonical path is "
                        + "META-INF/platform-tracing/agent-logging-schema.properties");
    }

    @Test
    void runtime_dependency_entry_collision_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("runtime-dependency-collision");
        addRuntimeDependency(
                fixture,
                "collision.jar",
                List.of(syntheticEntry(
                        "META-INF/platform-tracing/agent-logging-schema.properties",
                        new byte[] {1})));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("Extension packaging duplicate entry");
    }

    @Test
    void runtime_dependency_paths_and_topology_are_validated() throws IOException {
        // given
        List<List<SyntheticEntry>> attacks = List.of(
                List.of(syntheticEntry("../escape.class", new byte[] {1})),
                List.of(syntheticEntry("control-\u0001.class", new byte[] {1})),
                List.of(
                        syntheticEntry("collision", new byte[] {1}),
                        syntheticEntry("collision/child.class", new byte[] {2})));
        List<Fixture> fixtures = new ArrayList<>();
        for (int index = 0; index < attacks.size(); index++) {
            Fixture fixture = prepareFixture("runtime-dependency-path-" + index);
            addRuntimeDependency(fixture, "attack-" + index + ".jar", attacks.get(index));
            fixtures.add(fixture);
        }

        // when
        List<BuildResult> results = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            results.add(runAndFail(fixture));
        }

        // then
        assertThat(results.get(0).getOutput()).contains("path segment is not canonical");
        assertThat(results.get(1).getOutput()).contains("contains a control character");
        assertThat(results.get(2).getOutput()).contains("contains a file used as a ZIP parent");
    }

    @Test
    void runtime_dependency_metadata_is_filtered_before_packaging() throws IOException {
        // given
        Fixture fixture = prepareFixture("runtime-dependency-filtering");
        addRuntimeDependency(fixture, "filtered.jar", List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()),
                syntheticEntry("META-INF/DEPENDENCY.SF", new byte[] {1}),
                syntheticEntry("META-INF/DEPENDENCY.RSA", new byte[] {2}),
                syntheticEntry("META-INF/LICENSE.txt", new byte[] {3}),
                syntheticEntry("module-info.class", new byte[] {4}),
                syntheticEntry("META-INF/versions/9/module-info.class", new byte[] {5}),
                syntheticEntry("platform-log-event.schema.json", new byte[] {6}),
                syntheticEntry("dependency/Allowed.class", new byte[] {7})));

        // when
        run(fixture);
        Set<String> names = readEntryNames(fixture.packagedExtension());

        // then
        assertThat(names)
                .contains("dependency/Allowed.class")
                .doesNotContain(
                        "META-INF/DEPENDENCY.SF",
                        "META-INF/DEPENDENCY.RSA",
                        "META-INF/LICENSE.txt",
                        "module-info.class",
                        "META-INF/versions/9/module-info.class",
                        "platform-log-event.schema.json");
    }

    @Test
    void unexpected_opentelemetry_runtime_module_fails_closed() throws IOException {
        // given
        Fixture fixture = prepareFixture("unexpected-otel-runtime-module");
        addRuntimeDependency(
                fixture,
                "unexpected-otel.jar",
                "io.opentelemetry:opentelemetry-exporter-otlp:1.64.0",
                List.of(syntheticEntry("unexpected/OtelType.class", new byte[] {1})));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "Extension runtime dependency is not allowlisted: "
                        + "io.opentelemetry:opentelemetry-exporter-otlp:1.64.0");
    }

    @Test
    void unreviewed_jakarta_annotation_version_fails_closed() throws IOException {
        // given
        Fixture fixture = prepareFixture("unreviewed-jakarta-annotation-version");
        addRuntimeDependency(
                fixture,
                "unreviewed-jakarta-annotation.jar",
                "jakarta.annotation:jakarta.annotation-api:999.0.0",
                List.of(syntheticEntry("jakarta/annotation/Unreviewed.class", new byte[] {1})));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "Extension runtime dependency is not allowlisted: "
                        + "jakarta.annotation:jakarta.annotation-api:999.0.0");
    }

    @Test
    void aggregate_runtime_dependency_entries_are_bounded() throws IOException {
        // given
        Fixture fixture = prepareFixture("runtime-dependency-aggregate-bound");
        int maximumEntries = archiveMetrics(fixture.upstream()).entryCount();
        List<SyntheticEntry> entries = new ArrayList<>(maximumEntries);
        for (int index = 0; index < maximumEntries; index++) {
            entries.add(syntheticEntry("aggregate/entry-" + index, new byte[0]));
        }
        addRuntimeDependency(fixture, "aggregate.jar", entries);
        setIntegerTaskLimit(fixture, "maximumEntries", maximumEntries);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("Packaged platform extension entry count");
    }

    @Test
    void noncanonical_entry_name_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("noncanonical-entry");
        Path mutated = fixture.root().resolve("extension-mutated.jar");
        rewriteArchive(
                fixture.extension(),
                mutated,
                Map.of("../escape.class", new byte[] {1}),
                Set.of());
        Files.move(mutated, fixture.extension(), StandardCopyOption.REPLACE_EXISTING);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("ZIP entry path segment is not canonical");
    }

    @Test
    void preexisting_upstream_extension_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("preexisting-extension");
        Path mutated = fixture.root().resolve("upstream-mutated.jar");
        rewriteArchive(
                fixture.upstream(),
                mutated,
                Map.of("extensions/preexisting.jar", new byte[] {1}),
                Set.of());
        Files.move(mutated, fixture.upstream(), StandardCopyOption.REPLACE_EXISTING);
        updateReviewedUpstreamHash(fixture);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("already contains extension content");
    }

    @Test
    void case_aliased_upstream_extension_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("case-aliased-upstream-extension");
        Path mutated = fixture.root().resolve("upstream-mutated.jar");
        rewriteArchive(
                fixture.upstream(),
                mutated,
                Map.of("Extensions/preexisting.jar", new byte[] {1}),
                Set.of());
        Files.move(mutated, fixture.upstream(), StandardCopyOption.REPLACE_EXISTING);
        updateReviewedUpstreamHash(fixture);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("already contains extension content");
    }

    @Test
    void truncated_extension_archive_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("truncated-extension");
        byte[] extension = Files.readAllBytes(fixture.extension());
        Files.write(fixture.extension(), Arrays.copyOf(extension, extension.length - 128));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("has no valid end-of-central-directory record");
    }

    @Test
    void preexisting_reserved_identity_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("preexisting-identity");
        Path mutated = fixture.root().resolve("upstream-mutated.jar");
        rewriteArchive(
                fixture.upstream(),
                mutated,
                Map.of(
                        "META-INF/platform-tracing/javaagent-distribution.properties",
                        new byte[] {1}),
                Set.of());
        Files.move(mutated, fixture.upstream(), StandardCopyOption.REPLACE_EXISTING);
        updateReviewedUpstreamHash(fixture);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("already contains reserved entry");
    }

    @Test
    void missing_extension_manifest_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("missing-extension-manifest");
        Path mutated = fixture.root().resolve("extension-mutated.jar");
        rewriteArchive(
                fixture.extension(),
                mutated,
                Map.of(),
                Set.of("META-INF/MANIFEST.MF"));
        Files.move(mutated, fixture.extension(), StandardCopyOption.REPLACE_EXISTING);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("must contain exactly META-INF/MANIFEST.MF");
    }

    @Test
    void exact_duplicate_entry_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("exact-duplicate-entry");
        byte[] manifest = validExtensionManifest();
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", manifest),
                syntheticEntry("payload.bin", new byte[] {1}),
                syntheticEntry("payload.bin", new byte[] {2})));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("contains duplicate ZIP entry: payload.bin");
    }

    @Test
    void structural_entry_collisions_fail_without_partial_output() throws IOException {
        // given
        Fixture fileDirectoryAlias = prepareFixture("file-directory-alias");
        writeSyntheticArchive(fileDirectoryAlias.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()),
                syntheticEntry("collision", new byte[] {1}),
                syntheticEntry("collision/", new byte[0])));
        Fixture parentFile = prepareFixture("parent-file");
        writeSyntheticArchive(parentFile.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()),
                syntheticEntry("parent", new byte[] {1}),
                syntheticEntry("parent/child.class", new byte[] {2})));

        // when
        BuildResult aliasResult = runAndFail(fileDirectoryAlias);
        BuildResult parentResult = runAndFail(parentFile);

        // then
        assertThat(aliasResult.getOutput()).contains("structural ZIP entry collision");
        assertThat(parentResult.getOutput()).contains("file used as a ZIP parent");
    }

    @Test
    void manifest_case_alias_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("manifest-case-alias");
        byte[] manifest = validExtensionManifest();
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", manifest),
                syntheticEntry("meta-inf/manifest.mf", manifest)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("must contain exactly META-INF/MANIFEST.MF");
    }

    @Test
    void absolute_drive_backslash_and_control_paths_fail_without_partial_output() throws IOException {
        // given
        Map<String, String> invalidNames = Map.of(
                "absolute", "/escape.class",
                "drive", "C:/escape.class",
                "backslash", "nested\\escape.class",
                "control", "control-\u0001.class",
                "c1-control", "control-\u0085.class");
        List<BuildResult> results = new ArrayList<>();

        // when
        for (Map.Entry<String, String> invalid : invalidNames.entrySet()) {
            Fixture fixture = prepareFixture("invalid-path-" + invalid.getKey());
            writeSyntheticArchive(fixture.extension(), List.of(
                    syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()),
                    syntheticEntry(invalid.getValue(), new byte[] {1})));
            results.add(runAndFail(fixture));
        }

        // then
        assertThat(results).allSatisfy(result ->
                assertThat(result.getOutput()).contains("ZIP entry"));
    }

    @Test
    void extension_schema_hash_mismatch_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("extension-schema-hash-mismatch");
        Path rewritten = fixture.root().resolve("extension-rewritten.jar");
        rewriteArchive(
                fixture.extension(),
                rewritten,
                Map.of("platform-log-event.schema.json", "{}\n".getBytes(StandardCharsets.UTF_8)),
                Set.of("platform-log-event.schema.json"));
        Files.move(rewritten, fixture.extension(), StandardCopyOption.REPLACE_EXISTING);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("does not match identity");
    }

    @Test
    void unsafe_identity_property_value_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("unsafe-identity-property");
        Path buildScript = fixture.root().resolve("build.gradle");
        String original = Files.readString(buildScript, StandardCharsets.UTF_8);
        String expected = "distributionVersion.set('"
                + groovyString(requiredProperty("platformTracing.distributionVersion"))
                + "')";
        String replacement = "distributionVersion.set('"
                + groovyString("unsafe\\continuation")
                + "')";
        String updated = original.replace(expected, replacement);
        if (updated.equals(original)) {
            throw new IllegalStateException("Fixture distribution version was not replaced");
        }
        Files.writeString(buildScript, updated, StandardCharsets.UTF_8);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("Invalid controlled agent identity value");
    }

    @Test
    void extension_manifest_class_path_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("extension-manifest-class-path");
        byte[] manifest = extensionManifest(
                requiredProperty("platformTracing.distributionVersion"),
                "Class-Path: external.jar\r\n");
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", manifest)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "Platform extension library manifest must not declare Class-Path");
    }

    @Test
    void manifest_digest_material_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("manifest-digest-material");
        byte[] manifest = extensionManifest(
                requiredProperty("platformTracing.distributionVersion"),
                "SHA-256-Digest: YWJj\r\n");
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", manifest)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("manifest contains signature attribute");
    }

    @Test
    void invalid_utf8_manifest_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("invalid-utf8-manifest");
        byte[] manifest = validExtensionManifest();
        manifest[0] = (byte) 0xFF;
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", manifest)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "Platform extension library manifest must be valid UTF-8");
    }

    @Test
    void crc_corruption_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("crc-corruption");
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()),
                syntheticEntry("payload.bin", new byte[] {1, 2, 3})));
        byte[] archive = Files.readAllBytes(fixture.extension());
        corruptFirstEntryCrc(archive);
        Files.write(fixture.extension(), archive);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("CRC mismatch");
    }

    @Test
    void archive_size_limit_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("archive-size-limit");
        setLongTaskLimit(fixture, "maximumArchiveBytes", 1024L);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("is outside 1..1024 bytes");
    }

    @Test
    void bounded_candidate_write_failure_removes_stale_and_temporary_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("bounded-output-cleanup");
        long maximumArchiveBytes = Math.max(
                Files.size(fixture.upstream()), Files.size(fixture.extension()));
        setLongTaskLimit(fixture, "maximumArchiveBytes", maximumArchiveBytes);
        Files.createDirectories(fixture.output().getParent());
        Files.write(fixture.output(), new byte[] {1, 2, 3});

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("Controlled agent candidate exceeds");
        assertThat(fixture.output()).doesNotExist();
        assertNoTemporaryOutputs(fixture);
    }

    @Test
    void maximum_entries_rejects_forged_eocd_count() throws IOException {
        // given
        Fixture fixture = prepareFixture("maximum-entries");
        int maximumEntries = archiveMetrics(fixture.upstream()).entryCount();
        setIntegerTaskLimit(fixture, "maximumEntries", maximumEntries);
        int forgedEntries = Math.addExact(maximumEntries, 1);
        if (forgedEntries > 0xFFFF) {
            throw new IllegalStateException("Maximum entries fixture exceeds ZIP32 capacity");
        }
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest())));
        byte[] archive = Files.readAllBytes(fixture.extension());
        int endOfCentralDirectory = endOfCentralDirectoryOffset(archive);
        writeShortLittleEndian(archive, endOfCentralDirectory + 8, forgedEntries);
        writeShortLittleEndian(archive, endOfCentralDirectory + 10, forgedEntries);
        Files.write(fixture.extension(), archive);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "entry count " + forgedEntries + " is outside 1.." + maximumEntries);
    }

    @Test
    void maximum_entry_bytes_rejects_forged_central_size() throws IOException {
        // given
        Fixture fixture = prepareFixture("maximum-entry-bytes");
        long maximumEntryBytes = Math.max(
                archiveMetrics(fixture.upstream()).maximumEntryBytes(),
                validExtensionManifest().length);
        setLongTaskLimit(fixture, "maximumEntryBytes", maximumEntryBytes);
        if (maximumEntryBytes >= 0xFFFFFFFFL) {
            throw new IllegalStateException("Maximum entry bytes fixture exceeds ZIP32 capacity");
        }
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest())));
        byte[] archive = Files.readAllBytes(fixture.extension());
        int centralDirectory = firstCentralDirectoryOffset(archive);
        writeIntLittleEndian(archive, centralDirectory + 24, maximumEntryBytes + 1);
        Files.write(fixture.extension(), archive);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "exceeds " + maximumEntryBytes + " unpacked bytes");
    }

    @Test
    void maximum_uncompressed_bytes_rejects_forged_central_total() throws IOException {
        // given
        Fixture fixture = prepareFixture("maximum-uncompressed-bytes");
        ArchiveMetrics upstreamMetrics = archiveMetrics(fixture.upstream());
        long maximumEntryBytes = Math.max(
                upstreamMetrics.maximumEntryBytes(), validExtensionManifest().length);
        long maximumUncompressedBytes = upstreamMetrics.uncompressedBytes();
        setLongTaskLimit(fixture, "maximumEntryBytes", maximumEntryBytes);
        setLongTaskLimit(
                fixture, "maximumUncompressedBytes", maximumUncompressedBytes);
        int forgedEntryCount = Math.toIntExact(
                maximumUncompressedBytes / maximumEntryBytes + 1);
        List<SyntheticEntry> entries = new ArrayList<>(forgedEntryCount);
        entries.add(syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest()));
        for (int index = 1; index < forgedEntryCount; index++) {
            entries.add(syntheticEntry("payload-" + index + ".bin", new byte[] {1}));
        }
        writeSyntheticArchive(fixture.extension(), entries);
        byte[] archive = Files.readAllBytes(fixture.extension());
        forgeCentralEntrySizes(archive, maximumEntryBytes, maximumEntryBytes);
        Files.write(fixture.extension(), archive);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "exceeds " + maximumUncompressedBytes + " total unpacked bytes");
    }

    @Test
    void combined_upstream_and_addition_topology_fails_without_partial_output()
            throws IOException {
        // given
        Fixture fixture = prepareFixture("combined-topology");
        Path mutated = fixture.root().resolve("upstream-mutated.jar");
        rewriteArchive(
                fixture.upstream(),
                mutated,
                Map.of("META-INF/platform-tracing", new byte[] {1}),
                Set.of());
        Files.move(mutated, fixture.upstream(), StandardCopyOption.REPLACE_EXISTING);
        updateReviewedUpstreamHash(fixture);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains(
                "Controlled agent candidate contains a file used as a ZIP parent");
    }

    @Test
    void excessive_compression_ratio_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("compression-ratio-limit");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(fixture.extension()), StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            output.write(validExtensionManifest());
            output.closeEntry();
            output.putNextEntry(new ZipEntry("compressed-zeroes.bin"));
            output.write(new byte[1024 * 1024]);
            output.closeEntry();
        }

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("exceeds compression ratio 100");
    }

    @Test
    void encrypted_entry_flag_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("encrypted-entry");
        writeSyntheticArchive(fixture.extension(), List.of(new SyntheticEntry(
                "META-INF/MANIFEST.MF", validExtensionManifest(), 0x0801, ZipEntry.STORED)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("unsupported or encrypted ZIP flags");
    }

    @Test
    void zip64_size_marker_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("zip64-size-marker");
        writeSyntheticArchive(fixture.extension(), List.of(
                syntheticEntry("META-INF/MANIFEST.MF", validExtensionManifest())));
        byte[] archive = Files.readAllBytes(fixture.extension());
        int centralDirectory = firstCentralDirectoryOffset(archive);
        writeIntLittleEndian(archive, centralDirectory + 20, 0xFFFFFFFFL);
        Files.write(fixture.extension(), archive);

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("must not use ZIP64 or split entries");
    }

    @Test
    void unsupported_compression_method_fails_without_partial_output() throws IOException {
        // given
        Fixture fixture = prepareFixture("unsupported-method");
        writeSyntheticArchive(fixture.extension(), List.of(new SyntheticEntry(
                "META-INF/MANIFEST.MF", validExtensionManifest(), 0x0800, 99)));

        // when
        BuildResult result = runAndFail(fixture);

        // then
        assertThat(result.getOutput()).contains("uses unsupported compression method 99");
    }

    private Fixture prepareFixture(String name) throws IOException {
        Path root = temporaryDirectory.resolve(name);
        Files.createDirectories(root);
        Path upstream = copyRequiredFile("platformTracing.upstreamAgentJar", root.resolve("upstream.jar"));
        Path extension = copyRequiredFile(
                "platformTracing.extensionLibraryJar", root.resolve("extension.jar"));
        Files.writeString(
                root.resolve("settings.gradle"),
                "rootProject.name = 'controlled-agent-assembler-fixture'\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle"), fixtureBuildScript(), StandardCharsets.UTF_8);
        return new Fixture(root, upstream, extension, root.resolve("build/controlled-agent.jar"));
    }

    private static BuildResult run(Fixture fixture) {
        BuildResult result = GradleRunner.create()
                .withProjectDir(fixture.root().toFile())
                .withArguments("assembleControlledJavaAgent", "--no-build-cache", "--stacktrace")
                .build();
        assertThat(fixture.output()).isRegularFile();
        assertThat(fixture.packagedExtension()).isRegularFile();
        return result;
    }

    private static BuildResult runAndFail(Fixture fixture) {
        BuildResult result = GradleRunner.create()
                .withProjectDir(fixture.root().toFile())
                .withArguments("assembleControlledJavaAgent", "--no-build-cache", "--stacktrace")
                .buildAndFail();
        assertThat(fixture.output()).doesNotExist();
        assertThat(fixture.packagedExtension()).doesNotExist();
        return result;
    }

    private static void assertNoTemporaryOutputs(Fixture fixture) throws IOException {
        String prefix = "." + fixture.output().getFileName() + ".";
        try (Stream<Path> files = Files.list(fixture.output().getParent())) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList())
                    .isEmpty();
        }
    }

    private static void setLongTaskLimit(Fixture fixture, String property, long value)
            throws IOException {
        setTaskLimit(fixture, property, value + "L");
    }

    private static void setIntegerTaskLimit(Fixture fixture, String property, int value)
            throws IOException {
        setTaskLimit(fixture, property, Integer.toString(value));
    }

    private static void setTaskLimit(Fixture fixture, String property, String literal)
            throws IOException {
        Path buildScript = fixture.root().resolve("build.gradle");
        String original = Files.readString(buildScript, StandardCharsets.UTF_8);
        String marker = "distributionVersion.set('"
                + groovyString(requiredProperty("platformTracing.distributionVersion"))
                + "')";
        String updated = original.replace(
                marker,
                marker + "\n                    " + property + ".set(" + literal + ")");
        if (updated.equals(original)) {
            throw new IllegalStateException("Task limit fixture was not configured: " + property);
        }
        Files.writeString(buildScript, updated, StandardCharsets.UTF_8);
    }

    private static String fixtureBuildScript() {
        String assemblyScript = groovyPath(requiredPath("platformTracing.assemblyScript"));
        String version = groovyString(requiredProperty("platformTracing.distributionVersion"));
        return """
                apply from: '%s'

                tasks.register('assembleControlledJavaAgent', assembleControlledJavaAgentType) {
                    upstreamAgentJar.set(layout.projectDirectory.file('upstream.jar'))
                    extensionJar.set(layout.projectDirectory.file('extension.jar'))
                    extensionRuntimeClasspath.from(layout.projectDirectory.file('extension.jar'))
                    extensionRuntimeArtifactCoordinates.put(
                            'extension.jar',
                            'project::platform-starters-tracing:platform-tracing-otel-javaagent-extension')
                    outputJar.set(layout.buildDirectory.file('controlled-agent.jar'))
                    packagedExtensionJar.set(layout.buildDirectory.file('packaged-extension.jar'))
                    distributionVersion.set('%s')
                }
                """.formatted(assemblyScript, version);
    }

    private static void addRuntimeDependency(
            Fixture fixture,
            String fileName,
            List<SyntheticEntry> entries
    ) throws IOException {
        addRuntimeDependency(
                fixture,
                fileName,
                "project::platform-starters-common:platform-starters-common-util",
                entries);
    }

    private static void addRuntimeDependency(
            Fixture fixture,
            String fileName,
            String coordinate,
            List<SyntheticEntry> entries
    ) throws IOException {
        writeSyntheticArchive(fixture.root().resolve(fileName), entries);
        Path buildScript = fixture.root().resolve("build.gradle");
        String original = Files.readString(buildScript, StandardCharsets.UTF_8);
        String marker = "extensionRuntimeClasspath.from("
                + "layout.projectDirectory.file('extension.jar'))";
        String addition = marker + "\n"
                + "                    extensionRuntimeClasspath.from("
                + "layout.projectDirectory.file('" + groovyString(fileName) + "'))\n"
                + "                    extensionRuntimeArtifactCoordinates.put('"
                + groovyString(fileName) + "', "
                + "'" + groovyString(coordinate) + "')";
        String updated = original.replace(marker, addition);
        if (updated.equals(original)) {
            throw new IllegalStateException(
                    "Runtime dependency fixture was not configured: " + fileName);
        }
        Files.writeString(buildScript, updated, StandardCharsets.UTF_8);
    }

    private static void rewriteArchive(
            Path source,
            Path target,
            Map<String, byte[]> additions,
            Set<String> omissions
    ) throws IOException {
        try (ZipFile input = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream output = new ZipOutputStream(
                     Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry sourceEntry = entries.nextElement();
                if (omissions.contains(sourceEntry.getName())) {
                    continue;
                }
                output.putNextEntry(new ZipEntry(sourceEntry.getName()));
                output.write(input.getInputStream(sourceEntry).readAllBytes());
                output.closeEntry();
            }
            for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
                output.putNextEntry(new ZipEntry(addition.getKey()));
                output.write(addition.getValue());
                output.closeEntry();
            }
        }
    }

    private static Set<String> readEntryNames(Path archive) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static ArchiveMetrics archiveMetrics(Path archive) throws IOException {
        int entryCount = 0;
        long maximumEntryBytes = 0;
        long uncompressedBytes = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                long entryBytes = entry.getSize();
                if (entryBytes < 0) {
                    throw new IllegalStateException(
                            "Synthetic ZIP metric is unavailable for entry: " + entry.getName());
                }
                entryCount = Math.addExact(entryCount, 1);
                maximumEntryBytes = Math.max(maximumEntryBytes, entryBytes);
                uncompressedBytes = Math.addExact(uncompressedBytes, entryBytes);
            }
        }
        return new ArchiveMetrics(entryCount, maximumEntryBytes, uncompressedBytes);
    }

    private static void writeSyntheticArchive(Path target, List<SyntheticEntry> entries)
            throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        List<Integer> localOffsets = new ArrayList<>(entries.size());
        List<byte[]> encodedNames = new ArrayList<>(entries.size());
        List<Long> checksums = new ArrayList<>(entries.size());
        for (SyntheticEntry entry : entries) {
            byte[] encodedName = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.payload());
            localOffsets.add(archive.size());
            encodedNames.add(encodedName);
            checksums.add(crc.getValue());
            writeIntLittleEndian(archive, 0x04034B50L);
            writeShortLittleEndian(archive, 20);
            writeShortLittleEndian(archive, entry.flags());
            writeShortLittleEndian(archive, entry.method());
            writeShortLittleEndian(archive, 0);
            writeShortLittleEndian(archive, 0x5021);
            writeIntLittleEndian(archive, crc.getValue());
            writeIntLittleEndian(archive, entry.payload().length);
            writeIntLittleEndian(archive, entry.payload().length);
            writeShortLittleEndian(archive, encodedName.length);
            writeShortLittleEndian(archive, 0);
            archive.write(encodedName);
            archive.write(entry.payload());
        }

        int centralOffset = archive.size();
        for (int index = 0; index < entries.size(); index++) {
            SyntheticEntry entry = entries.get(index);
            byte[] encodedName = encodedNames.get(index);
            writeIntLittleEndian(archive, 0x02014B50L);
            writeShortLittleEndian(archive, 20);
            writeShortLittleEndian(archive, 20);
            writeShortLittleEndian(archive, entry.flags());
            writeShortLittleEndian(archive, entry.method());
            writeShortLittleEndian(archive, 0);
            writeShortLittleEndian(archive, 0x5021);
            writeIntLittleEndian(archive, checksums.get(index));
            writeIntLittleEndian(archive, entry.payload().length);
            writeIntLittleEndian(archive, entry.payload().length);
            writeShortLittleEndian(archive, encodedName.length);
            writeShortLittleEndian(archive, 0);
            writeShortLittleEndian(archive, 0);
            writeShortLittleEndian(archive, 0);
            writeShortLittleEndian(archive, 0);
            writeIntLittleEndian(archive, 0);
            writeIntLittleEndian(archive, localOffsets.get(index));
            archive.write(encodedName);
        }

        int centralSize = archive.size() - centralOffset;
        writeIntLittleEndian(archive, 0x06054B50L);
        writeShortLittleEndian(archive, 0);
        writeShortLittleEndian(archive, 0);
        writeShortLittleEndian(archive, entries.size());
        writeShortLittleEndian(archive, entries.size());
        writeIntLittleEndian(archive, centralSize);
        writeIntLittleEndian(archive, centralOffset);
        writeShortLittleEndian(archive, 0);
        Files.write(target, archive.toByteArray());
    }

    private static SyntheticEntry syntheticEntry(String name, byte[] payload) {
        return new SyntheticEntry(name, payload, 0x0800, ZipEntry.STORED);
    }

    private static byte[] validExtensionManifest() {
        return extensionManifest(requiredProperty("platformTracing.distributionVersion"), "");
    }

    private static byte[] extensionManifest(String version, String additionalHeaders) {
        String manifest = "Manifest-Version: 1.0\r\n"
                + "Implementation-Title: platform-tracing-otel-javaagent-extension\r\n"
                + "Implementation-Version: " + version + "\r\n"
                + additionalHeaders
                + "\r\n";
        return manifest.getBytes(StandardCharsets.UTF_8);
    }

    private static void corruptFirstEntryCrc(byte[] archive) {
        int centralDirectory = firstCentralDirectoryOffset(archive);
        int localHeader = Math.toIntExact(readUnsignedIntLittleEndian(archive, centralDirectory + 42));
        long checksum = readUnsignedIntLittleEndian(archive, centralDirectory + 16);
        long corrupted = (checksum + 1) & 0xFFFFFFFFL;
        writeIntLittleEndian(archive, centralDirectory + 16, corrupted);
        writeIntLittleEndian(archive, localHeader + 14, corrupted);
    }

    private static void forgeCentralEntrySizes(
            byte[] archive,
            long compressedSize,
            long uncompressedSize
    ) {
        if (compressedSize < 0 || compressedSize > 0xFFFFFFFFL
                || uncompressedSize < 0 || uncompressedSize > 0xFFFFFFFFL) {
            throw new IllegalStateException("Synthetic ZIP size is outside ZIP32 capacity");
        }
        int endOfCentralDirectory = endOfCentralDirectoryOffset(archive);
        int entryCount = readUnsignedShortLittleEndian(archive, endOfCentralDirectory + 10);
        int cursor = Math.toIntExact(
                readUnsignedIntLittleEndian(archive, endOfCentralDirectory + 16));
        for (int index = 0; index < entryCount; index++) {
            if (readUnsignedIntLittleEndian(archive, cursor) != 0x02014B50L) {
                throw new IllegalStateException(
                        "Synthetic ZIP central directory entry is missing");
            }
            writeIntLittleEndian(archive, cursor + 20, compressedSize);
            writeIntLittleEndian(archive, cursor + 24, uncompressedSize);
            int nameLength = readUnsignedShortLittleEndian(archive, cursor + 28);
            int extraLength = readUnsignedShortLittleEndian(archive, cursor + 30);
            int commentLength = readUnsignedShortLittleEndian(archive, cursor + 32);
            cursor += 46 + nameLength + extraLength + commentLength;
        }
    }

    private static int firstCentralDirectoryOffset(byte[] archive) {
        int endOfCentralDirectory = endOfCentralDirectoryOffset(archive);
        return Math.toIntExact(
                readUnsignedIntLittleEndian(archive, endOfCentralDirectory + 16));
    }

    private static int endOfCentralDirectoryOffset(byte[] archive) {
        for (int offset = archive.length - 22; offset >= 0; offset--) {
            if (readUnsignedIntLittleEndian(archive, offset) == 0x06054B50L) {
                return offset;
            }
        }
        throw new IllegalStateException("Synthetic ZIP end-of-central-directory record is missing");
    }

    private static int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) {
            throw new IllegalStateException(
                    "Synthetic ZIP short offset is outside the archive: " + offset);
        }
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long readUnsignedIntLittleEndian(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new IllegalStateException("Synthetic ZIP integer offset is outside the archive: " + offset);
        }
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static void writeShortLittleEndian(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeShortLittleEndian(byte[] bytes, int offset, int value) {
        if (offset < 0 || offset + 2 > bytes.length) {
            throw new IllegalStateException(
                    "Synthetic ZIP short offset is outside the archive: " + offset);
        }
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) ((value >>> 16) & 0xFF));
        output.write((int) ((value >>> 24) & 0xFF));
    }

    private static void writeIntLittleEndian(byte[] bytes, int offset, long value) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new IllegalStateException("Synthetic ZIP integer offset is outside the archive: " + offset);
        }
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void updateReviewedUpstreamHash(Fixture fixture) throws IOException {
        String original = new String(
                readArchiveEntry(fixture.extension(), ACP_PIN_RESOURCE), StandardCharsets.UTF_8);
        List<String> reviewedLines = original.lines()
                .filter(line -> line.startsWith("upstream.sha256="))
                .toList();
        if (reviewedLines.size() != 1) {
            throw new IllegalStateException(
                    "ACP pin resource must contain exactly one upstream.sha256 line");
        }
        replaceExtensionPinText(
                fixture,
                reviewedLines.getFirst(),
                "upstream.sha256=" + sha256Hex(Files.readAllBytes(fixture.upstream())));
    }

    private static Path copyRequiredFile(String property, Path target) throws IOException {
        Path source = requiredPath(property);
        Files.copy(source, target);
        return target;
    }

    private static void replaceExtensionPinText(
            Fixture fixture,
            String current,
            String replacement
    ) throws IOException {
        String original = new String(
                readArchiveEntry(fixture.extension(), ACP_PIN_RESOURCE), StandardCharsets.UTF_8);
        String updated = original.replace(current, replacement);
        if (updated.equals(original)) {
            throw new IllegalStateException("ACP pin resource text was not replaced: " + current);
        }
        replaceExtensionPin(fixture, updated.getBytes(StandardCharsets.UTF_8));
    }

    private static void replaceExtensionPin(Fixture fixture, byte[] payload) throws IOException {
        Path rewritten = fixture.root().resolve("extension-pin-rewritten.jar");
        rewriteArchive(
                fixture.extension(),
                rewritten,
                Map.of(ACP_PIN_RESOURCE, payload),
                Set.of(ACP_PIN_RESOURCE));
        Files.move(rewritten, fixture.extension(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readArchiveEntry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("Required ZIP entry is missing: " + name);
            }
            if (entry.isDirectory()) {
                throw new IllegalStateException("Required ZIP entry is a directory: " + name);
            }
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }
        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }

    private static String groovyPath(Path path) {
        return groovyString(path.toAbsolutePath().normalize().toString().replace('\\', '/'));
    }

    private static String groovyString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record ArchiveMetrics(
            int entryCount,
            long maximumEntryBytes,
            long uncompressedBytes
    ) {
    }

    private record SyntheticEntry(String name, byte[] payload, int flags, int method) {
    }

    private record Fixture(Path root, Path upstream, Path extension, Path output) {

        private Path packagedExtension() {
            return root.resolve("build/packaged-extension.jar");
        }
    }
}
