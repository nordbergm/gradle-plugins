package co.elastic.gradle.dockerbase;

import co.elastic.gradle.GradleTestkitHelper;
import co.elastic.gradle.TestkitIntegrationTest;
import co.elastic.gradle.utils.Architecture;
import org.apache.commons.io.IOUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static co.elastic.gradle.AssertContains.assertContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class DockerBaseImageBuildPluginIT extends TestkitIntegrationTest {

    // Todo: When we are sure Artifactory is reliable, e.g.  we do our own publishing as part of lockfile generation we
    //      should add a test an additional validation, with a checked in lockfile that in time will prove that
    //      packages are not updated and still awaitable as time goes by

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu:20.04", "centos:7", "debian:11"})
    public void testSingleProject(String baseImages, @TempDir Path testProjectDir) throws IOException, InterruptedException {
        final GradleTestkitHelper helper = getHelper(testProjectDir);
        final GradleRunner gradleRunner = getGradleRunner(testProjectDir);

        Set<String> imagesInDaemonAlreadyThere = getImagesInDaemon();

        helper.writeFile("image_content/foo.txt", "sample content");
        writeSimpleBuildScript(helper, baseImages);
        runGradleTask(gradleRunner, "dockerBaseImageLockfile");
        runGradleTask(gradleRunner, "dockerLocalImport");

        System.out.println("Running verification script...");
        Files.copy(
                Objects.requireNonNull(getClass().getResourceAsStream("/test_created_image.sh")),
                helper.projectDir().resolve("test_created_image.sh")
        );
        Files.setPosixFilePermissions(
                helper.projectDir().resolve("test_created_image.sh"),
                PosixFilePermissions.fromString("r-xr-xr-x")
        );
        final Process process = new ProcessBuilder()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .directory(helper.projectDir().toFile())
                .command(helper.projectDir().resolve("test_created_image.sh").toString())
                .start();

        do {
            IOUtils.copy(process.getInputStream(), System.out);
            IOUtils.copy(process.getErrorStream(), System.err);
        } while (process.isAlive());


        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            Assertions.fail("Verification script failed with exit code: " + exitCode);
        }
        System.out.println("Verification script completed successfully...");

        final Set<String> imagesInDaemonRightBeforeClean = getImagesInDaemon();
        final String expectedLocalTag = "gradle-test-local/" + helper.projectDir().getFileName() + "-base:latest";
        if (!imagesInDaemonRightBeforeClean.contains(expectedLocalTag)) {
            fail("Expected " + expectedLocalTag + " to be present in the daemon after local import but it was not");
        }

        runGradleTask(gradleRunner, "dockerBaseImageClean");


        Set<String> imagesInDaemonAfterClean = getImagesInDaemon();
        imagesInDaemonAfterClean.removeAll(imagesInDaemonAlreadyThere);
        if (!imagesInDaemonAfterClean.isEmpty()) {
            // There aren't a lot of great ways to test this, and this one might be too fragile as other tests might add
            // images that make this fail ...
            fail("Expected clean task to clean up everything but daemon was left with " + imagesInDaemonAfterClean);
        }
    }

    private Set<String> getImagesInDaemon() throws IOException {
        final Process result = new ProcessBuilder()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .command("docker", "image", "ls", "--format", "{{.Repository}}:{{.Tag}}")
                .start();
        Set<String> imagesInDaemon = new HashSet<>();
        do {
            IOUtils.copy(result.getErrorStream(), System.err);
        } while (result.isAlive());
        final BufferedReader lineReader = new BufferedReader(new InputStreamReader(result.getInputStream()));
        for (String line = lineReader.readLine(); line != null; line = lineReader.readLine()) {
            imagesInDaemon.add(line.trim());
        }
        return imagesInDaemon;
    }

    @Test
    public void testMultiProject() throws IOException {
        helper.settings("""
                include("s1")
                include("s2")
                include("s3")
                """
        );
        helper.buildScript(String.format("""
                import java.net.URL
                import %s
                                
                evaluationDependsOnChildren()
                                
                plugins {
                   id("co.elastic.vault")
                }
                vault {
                      address.set("https://secrets.elastic.co:8200")
                      auth {
                        ghTokenFile()
                        ghTokenEnv()
                        tokenEnv()
                        roleAndSecretEnv()
                      }
                }
                val creds = vault.readAndCacheSecret("secret/cloud-team/cloud-ci/artifactory_creds").get()
                                
                subprojects {
                    print(project.name)
                    configure<BaseImageExtension> {
                        mirrorBaseURL.set(URL("https://${creds["username"]}:${creds["plaintext"]}@artifactory.elastic.dev/artifactory/"))
                    }
                }
                """, BaseImageExtension.class.getName()
        ));

        helper.buildScript("s1", """
                plugins {
                   id("co.elastic.docker-base")
                }
                dockerBaseImage {
                  fromUbuntu("ubuntu", "20.04")
                  install("patch")
                }
                """
        );
        helper.buildScript("s2", """
                plugins {
                   id("co.elastic.docker-base")
                }
                dockerBaseImage {
                  from(project(":s1"))
                  run("patch --version")
                  install("jq")
                }
                """
        );
        helper.buildScript("s3", """
                plugins {
                   id("co.elastic.docker-base")
                }
                dockerBaseImage {
                  from(project(":s2"))
                  run("jq --version")
                }
                """
        );

        runGradleTask(gradleRunner, "dockerBaseImageLockfile");
        runGradleTask(gradleRunner, "dockerLocalImport");
    }

    @Test
    public void testPullTask() throws IOException {
        Files.createDirectories(helper.projectDir().resolve("s1"));
        Files.copy(
                Objects.requireNonNull(getClass().getResourceAsStream("/ubuntu.lockfile.yaml")),
                helper.projectDir().resolve("s1/docker-base-image.lock")
        );
        helper.settings("""
                include("s1")
                include("s2")
                """
        );
        helper.buildScript("s1", """
                plugins {
                    id("co.elastic.docker-base")
                }
                dockerBaseImage {
                      fromUbuntu("ubuntu", "20.04")
                }
                """
        );
        helper.buildScript("s2", """
                plugins {
                    id("co.elastic.docker-base")
                }
                dockerBaseImage {
                    from(project(":s1"))
                }
                """
        );
        final BuildResult result = gradleRunner.withArguments("--warning-mode", "fail", "-s", "dockerBasePull").build();
        assertContains(result.getOutput(), "Pulling from library/ubuntu");
        assertContains(
                result.getOutput(),
                Architecture.current().map(Map.of(
                        Architecture.AARCH64, "sha256:a51c8bb81605567ea27d627425adf94a613d675a664bf473d43a55a8a26416b8",
                        Architecture.X86_64, "sha256:31cd7bbfd36421dfd338bceb36d803b3663c1bfa87dfe6af7ba764b5bf34de05"
                ))
        );
        assertEquals(TaskOutcome.SKIPPED, Objects.requireNonNull(result.task(":s2:dockerBasePull")).getOutcome());
    }

    @Test
    public void testDockerEphemeralConfig() throws IOException {
        helper.buildScript("""
                import java.net.URL
                plugins {
                   id("co.elastic.docker-base")
                   id("co.elastic.vault")
                }
                vault {
                      address.set("https://secrets.elastic.co:8200")
                      auth {
                        ghTokenFile()
                        ghTokenEnv()
                        tokenEnv()
                        roleAndSecretEnv()
                      }
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                   dockerEphemeral("org.slf4j:slf4j-api:1.7.36")
                }
                val creds = vault.readAndCacheSecret("secret/cloud-team/cloud-ci/artifactory_creds").get()
                dockerBaseImage {
                    mirrorBaseURL.set(URL("https://${creds["username"]}:${creds["plaintext"]}@artifactory.elastic.dev/artifactory/"))
                    fromUbuntu("ubuntu", "20.04")
                    run(
                        "ls $dockerEphemeral/slf4j-api-1.7.36.jar",
                    )
                }
                """
        );
        Files.copy(
                Objects.requireNonNull(getClass().getResourceAsStream("/ubuntu.lockfile.yaml")),
                helper.projectDir().resolve("docker-base-image.lock")
        );
        final BuildResult result = gradleRunner.withArguments("--warning-mode", "fail", "-s", "dockerBaseImageBuild").build();
        assertContains(result.getOutput(), "slf4j-api-1.7.36.jar");
    }

    private BuildResult runGradleTask(GradleRunner gradleRunner, String task) throws IOException {
        try {
            return gradleRunner.withArguments("--warning-mode", "fail", "-s", task).build();
        } finally {
            System.out.println("Listing of project dir:");
            Set<String> fileNamesOfInterest = Set.of("docker-base-image.lock", "Dockerfile", ".dockerignore");
            try (Stream<Path> s = Files.walk(helper.projectDir()).filter(each -> !each.toString().contains(".gradle"))) {
                s.forEach(each -> {
                    if (fileNamesOfInterest.contains(each.getFileName().toString())) {
                        System.out.println("Content of: " + helper.projectDir().relativize(each) + "\n");
                        try {
                            IOUtils.copy(Files.newInputStream(each), System.out);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        System.out.println("\n");
                    } else {
                        System.out.println(helper.projectDir().relativize(each));
                    }
                });
            }
        }
    }

    private void writeSimpleBuildScript(GradleTestkitHelper helper, String baseImages) {
        final String[] from = baseImages.split(":");
        assertEquals(2, from.length);
        helper.buildScript(String.format("""
                import java.net.URL
                plugins {
                   id("co.elastic.docker-base")
                   id("co.elastic.vault")
                }
                vault {
                      address.set("https://secrets.elastic.co:8200")
                      auth {
                        ghTokenFile()
                        ghTokenEnv()
                        tokenEnv()
                        roleAndSecretEnv()
                      }
                }
                val creds = vault.readAndCacheSecret("secret/cloud-team/cloud-ci/artifactory_creds").get()
                dockerBaseImage {
                    dockerTagLocalPrefix.set("gradle-test-local")
                    mirrorBaseURL.set(URL("https://${creds["username"]}:${creds["plaintext"]}@artifactory.elastic.dev/artifactory/"))
                    from%s("%s", "%s")
                    install("patch")
                    copySpec("1234:1234") {
                       from(fileTree("image_content")) {
                          into("home/foobar")
                       }
                    }
                    copySpec() {
                        from(projectDir) {
                           include("build.gradle.kts")
                        }
                        into("home/foobar")
                    }
                    healthcheck("/home/foobar/foo.txt")
                    env("MYVAR_PROJECT" to project.name)
                    createUser("foobar", 1234, "foobar", 1234)
                    run(
                        "ls -Ral /home",
                        "echo \\"This plugin rocks on $architecture and ephemeral files are available at $dockerEphemeral!\\" > /home/foobar/bar.txt"
                    )
                    run(listOf(
                        "touch /home/foobar/run.listOf.1",
                        "touch /home/foobar/run.listOf.2",
                        "chmod -R 777 /home"
                    ))
                    setUser("foobar")
                    install("jq", "sudo")
                    if ("%s" == "centos") {
                       install("which")
                    }
                    run("whoami > /home/foobar/whoami")
                }
                """, from[0].substring(0, 1).toUpperCase() + from[0].substring(1), from[0], from[1], from[0])
        );
    }

}
