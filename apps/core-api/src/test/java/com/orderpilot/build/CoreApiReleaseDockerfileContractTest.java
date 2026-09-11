package com.orderpilot.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CoreApiReleaseDockerfileContractTest {

  @Test
  void releaseDockerfilePackagesOnlyAfterVerifyStage() throws IOException {
    String text = Files.readString(resolveDockerfile());

    assertThat(text).contains("AS verify");
    assertThat(text).contains("FROM verify AS package");

    int verifyStage = text.indexOf("AS verify");
    int testCommand = text.indexOf("RUN mvn -q -B test");
    int packageStage = text.indexOf("FROM verify AS package");
    int skipTestsPackage = text.indexOf("RUN mvn -q -B -DskipTests package");

    assertThat(verifyStage).isGreaterThanOrEqualTo(0);
    assertThat(testCommand).isGreaterThan(verifyStage);
    assertThat(packageStage).isGreaterThan(testCommand);
    assertThat(skipTestsPackage).isGreaterThan(packageStage);
  }

  @Test
  void dockerignoreDoesNotStripJavaSourcePackagesFromReleaseContext() throws IOException {
    Path dockerignore = resolveRepoRoot().resolve(".dockerignore");
    assertThat(Files.exists(dockerignore)).as(".dockerignore present at repo root").isTrue();

    // Read only the non-comment, non-blank ignore patterns.
    var patterns =
        Files.readAllLines(dockerignore).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();

    // Depth-agnostic `**/out/` and `**/build/` also match Java source packages
    // (application/port/out, com/orderpilot/build) that the release image's verify
    // stage compiles via `mvn test`. They must never appear in that form.
    assertThat(patterns)
        .as("no depth-agnostic pattern may strip a Java source package from the Docker context")
        .doesNotContain("**/out/", "**/out", "**/build/", "**/build");

    // The outbound port package that broke the in-image compile must exist as source
    // and must not be excluded by any surviving pattern's leaf name.
    Path portOut =
        resolveRepoRoot()
            .resolve("apps/core-api/src/main/java/com/orderpilot/aibot/application/port/out");
    assertThat(Files.isDirectory(portOut)).as("aibot outbound port/out source package present").isTrue();
  }

  private static Path resolveDockerfile() {
    Path cwd = Path.of("").toAbsolutePath();

    Path fromCoreApi = cwd.resolve("Dockerfile");
    if (Files.exists(fromCoreApi)) {
      return fromCoreApi;
    }

    Path fromRepoRoot = cwd.resolve("apps/core-api/Dockerfile");
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }

    throw new IllegalStateException("Unable to locate apps/core-api/Dockerfile from " + cwd);
  }

  private static Path resolveRepoRoot() {
    Path cwd = Path.of("").toAbsolutePath();
    // Tests run from apps/core-api (Maven module dir) or the repo root.
    if (Files.exists(cwd.resolve(".dockerignore"))) {
      return cwd;
    }
    Path parent = cwd.getParent();
    if (parent != null && Files.exists(parent.resolve(".dockerignore"))) {
      return parent;
    }
    Path grandparent = parent != null ? parent.getParent() : null;
    if (grandparent != null && Files.exists(grandparent.resolve(".dockerignore"))) {
      return grandparent;
    }
    throw new IllegalStateException("Unable to locate repo-root .dockerignore from " + cwd);
  }
}