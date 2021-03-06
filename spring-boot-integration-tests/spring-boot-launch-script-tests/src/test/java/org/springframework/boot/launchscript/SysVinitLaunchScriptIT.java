/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.launchscript;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.DockerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.CompressArchiveUtil;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.jaxrs.AbstrSyncDockerCmdExec;
import com.github.dockerjava.jaxrs.DockerCmdExecFactoryImpl;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.springframework.boot.ansi.AnsiColor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

/**
 * Integration tests for Spring Boot's launch script on OSs that use SysVinit.
 *
 * @author Andy Wilkinson
 */
@RunWith(Parameterized.class)
public class SysVinitLaunchScriptIT {

	private final SpringBootDockerCmdExecFactory commandExecFactory = new SpringBootDockerCmdExecFactory();

	private static final char ESC = 27;

	private final String os;

	private final String version;

	@Parameters(name = "{0} {1}")
	public static List<Object[]> parameters() {
		List<Object[]> parameters = new ArrayList<Object[]>();
		for (File os : new File("src/test/resources/conf").listFiles()) {
			for (File version : os.listFiles()) {
				parameters.add(new Object[] { os.getName(), version.getName() });
			}
		}
		return parameters;
	}

	public SysVinitLaunchScriptIT(String os, String version) {
		this.os = os;
		this.version = version;
	}

	@Test
	public void statusWhenStopped() throws Exception {
		String output = doTest("status-when-stopped.sh");
		assertThat(output, containsString("Status: 3"));
		assertThat(output, containsColoredString(AnsiColor.RED, "Not running"));
	}

	@Test
	public void statusWhenStarted() throws Exception {
		String output = doTest("status-when-started.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Started [" + extractPid(output) + "]"));
	}

	@Test
	public void statusWhenKilled() throws Exception {
		String output = doTest("status-when-killed.sh");
		assertThat(output, containsString("Status: 1"));
		assertThat(output, containsColoredString(AnsiColor.RED,
				"Not running (process " + extractPid(output) + " not found)"));
	}

	@Test
	public void stopWhenStopped() throws Exception {
		String output = doTest("stop-when-stopped.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.YELLOW,
				"Not running (pidfile not found)"));
	}

	@Test
	public void startWhenStarted() throws Exception {
		String output = doTest("start-when-started.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.YELLOW,
				"Already running [" + extractPid(output) + "]"));
	}

	@Test
	public void restartWhenStopped() throws Exception {
		String output = doTest("restart-when-stopped.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.YELLOW,
				"Not running (pidfile not found)"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Started [" + extractPid(output) + "]"));
	}

	@Test
	public void restartWhenStarted() throws Exception {
		String output = doTest("restart-when-started.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Started [" + extract("PID1", output) + "]"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Stopped [" + extract("PID1", output) + "]"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Started [" + extract("PID2", output) + "]"));
	}

	@Test
	public void startWhenStopped() throws Exception {
		String output = doTest("start-when-stopped.sh");
		assertThat(output, containsString("Status: 0"));
		assertThat(output, containsColoredString(AnsiColor.GREEN,
				"Started [" + extractPid(output) + "]"));
	}

	@Test
	public void basicLaunch() throws Exception {
		doLaunch("basic-launch.sh");
	}

	@Test
	public void launchWithSingleCommandLineArgument() throws Exception {
		doLaunch("launch-with-single-command-line-argument.sh");
	}

	@Test
	public void launchWithMultipleCommandLineArguments() throws Exception {
		doLaunch("launch-with-multiple-command-line-arguments.sh");
	}

	@Test
	public void launchWithSingleRunArg() throws Exception {
		doLaunch("launch-with-single-run-arg.sh");
	}

	@Test
	public void launchWithMultipleRunArgs() throws Exception {
		doLaunch("launch-with-multiple-run-args.sh");
	}

	@Test
	public void launchWithSingleJavaOpt() throws Exception {
		doLaunch("launch-with-single-java-opt.sh");
	}

	@Test
	public void launchWithMultipleJavaOpts() throws Exception {
		doLaunch("launch-with-multiple-java-opts.sh");
	}

	@Test
	public void launchWithUseOfStartStopDaemonDisabled() throws Exception {
		// CentOS doesn't have start-stop-daemon
		assumeThat(this.os, is(not("CentOS")));
		doLaunch("launch-with-use-of-start-stop-daemon-disabled.sh");
	}

	private void doLaunch(String script) throws Exception {
		assertThat(doTest(script), containsString("Launched"));
	}

	private String doTest(String script) throws Exception {
		DockerClient docker = createClient();
		String imageId = buildImage(docker);
		String container = createContainer(docker, imageId, script);
		copyFilesToContainer(docker, container, script);
		docker.startContainerCmd(container).exec();
		StringBuilder output = new StringBuilder();
		AttachContainerResultCallback resultCallback = docker
				.attachContainerCmd(container).withStdOut(true).withStdErr(true)
				.withFollowStream(true).withLogs(true)
				.exec(new AttachContainerResultCallback() {

					@Override
					public void onNext(Frame item) {
						output.append(new String(item.getPayload()));
						super.onNext(item);
					}

				});
		resultCallback.awaitCompletion(60, TimeUnit.SECONDS).close();
		docker.waitContainerCmd(container).exec();
		return output.toString();
	}

	private DockerClient createClient() {
		DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
				.build();
		DockerClient docker = DockerClientBuilder.getInstance(config)
				.withDockerCmdExecFactory(this.commandExecFactory).build();
		return docker;
	}

	private String buildImage(DockerClient docker) {
		BuildImageResultCallback resultCallback = new BuildImageResultCallback();
		String dockerfile = "src/test/resources/conf/" + this.os + "/" + this.version
				+ "/Dockerfile";
		docker.buildImageCmd(new File(dockerfile)).exec(resultCallback);
		String imageId = resultCallback.awaitImageId();
		return imageId;
	}

	private String createContainer(DockerClient docker, String imageId,
			String testScript) {
		return docker.createContainerCmd(imageId).withTty(false).withCmd("/bin/bash",
				"-c", "chmod +x " + testScript + " && ./" + testScript).exec().getId();
	}

	private void copyFilesToContainer(DockerClient docker, final String container,
			String script) {
		copyToContainer(docker, container, findApplication());
		copyToContainer(docker, container,
				new File("src/test/resources/scripts/test-functions.sh"));
		copyToContainer(docker, container,
				new File("src/test/resources/scripts/" + script));
	}

	private void copyToContainer(DockerClient docker, final String container,
			final File file) {
		this.commandExecFactory.createCopyToContainerCmdExec()
				.exec(new CopyToContainerCmd(container, file));
	}

	private File findApplication() {
		File targetDir = new File("target");
		for (File file : targetDir.listFiles()) {
			if (file.getName().startsWith("spring-boot-launch-script-tests")
					&& file.getName().endsWith(".jar")
					&& !file.getName().endsWith("-sources.jar")) {
				return file;
			}
		}
		throw new IllegalStateException(
				"Could not find test application in target directory. Have you built it (mvn package)?");
	}

	private Matcher<String> containsColoredString(AnsiColor color, String string) {
		return containsString(ESC + "[0;" + color + "m" + string + ESC + "[0m");
	}

	private String extractPid(String output) {
		return extract("PID", output);
	}

	private String extract(String label, String output) {
		Pattern pattern = Pattern.compile(".*" + label + ": ([0-9]+).*", Pattern.DOTALL);
		java.util.regex.Matcher matcher = pattern.matcher(output);
		if (matcher.matches()) {
			return matcher.group(1);
		}
		throw new IllegalArgumentException(
				"Failed to extract " + label + " from output: " + output);
	}

	private static final class CopyToContainerCmdExec
			extends AbstrSyncDockerCmdExec<CopyToContainerCmd, Void> {

		private CopyToContainerCmdExec(WebTarget baseResource,
				DockerClientConfig dockerClientConfig) {
			super(baseResource, dockerClientConfig);
		}

		@Override
		protected Void execute(CopyToContainerCmd command) {
			try {
				InputStream streamToUpload = new FileInputStream(CompressArchiveUtil
						.archiveTARFiles(command.getFile().getParentFile(),
								Arrays.asList(command.getFile()),
								command.getFile().getName()));
				WebTarget webResource = getBaseResource().path("/containers/{id}/archive")
						.resolveTemplate("id", command.getContainer());
				webResource.queryParam("path", ".")
						.queryParam("noOverwriteDirNonDir", false).request()
						.put(Entity.entity(streamToUpload, "application/x-tar")).close();
				return null;
			}
			catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}

	}

	private static final class CopyToContainerCmd implements DockerCmd<Void> {

		private final String container;

		private final File file;

		private CopyToContainerCmd(String container, File file) {
			this.container = container;
			this.file = file;
		}

		public String getContainer() {
			return this.container;
		}

		public File getFile() {
			return this.file;
		}

		@Override
		public void close() {

		}

	}

	private static final class SpringBootDockerCmdExecFactory
			extends DockerCmdExecFactoryImpl {

		private SpringBootDockerCmdExecFactory() {
			withClientRequestFilters(new ClientRequestFilter() {

				@Override
				public void filter(ClientRequestContext requestContext)
						throws IOException {
					// Workaround for https://go-review.googlesource.com/#/c/3821/
					requestContext.getHeaders().add("Connection", "close");
				}

			});
		}

		private CopyToContainerCmdExec createCopyToContainerCmdExec() {
			return new CopyToContainerCmdExec(getBaseResource(), getDockerClientConfig());
		}

	}

}
