/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.gradle.buildscan.xunit;

import org.gradle.api.GradleException;
import org.gradle.api.internal.tasks.testing.*;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.id.LongIdGenerator;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public class ExternalTestExecuter implements TestExecuter<TestExecutionSpec> {

    private final Set<File> fromFiles;
    private static final Logger logger = Logging.getLogger(ExternalTestExecuter.class);

    public ExternalTestExecuter(Set<File> fromFile) {
        this.fromFiles = fromFile;

    }

    @Override
    public void execute(TestExecutionSpec testExecutionSpec, TestResultProcessor processor) {
        List<String> missingFiles = fromFiles.stream()
                .filter(file -> !file.exists())
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
        if (missingFiles.size() > 0) {
            throw new GradleException("Can't find input files " + String.join(" ", missingFiles));
        }

        IdGenerator<?> idGenerator = new LongIdGenerator();

        fromFiles.stream()
                .peek(file -> logger.lifecycle("Loading results from {}", file))
                .flatMap(resultFile -> XUnitXmlParser.parse(resultFile).stream())
                .forEach(testSuite -> {
                    DefaultTestClassDescriptor suiteDescriptor = new DefaultTestClassDescriptor(idGenerator.generateId(), testSuite.getName());
                    LocalDateTime suiteStartTime = testSuite.startedTime(LocalDateTime::now);
                    processor.started(suiteDescriptor, new TestStartEvent(toEpochMilli(suiteStartTime)));

                    testSuite.getTests().forEach(testCase -> {

                        DefaultTestMethodDescriptor methodDescriptor = new DefaultTestMethodDescriptor(
                                idGenerator.generateId(),
                                testSuite.getName(),
                                testCase.getName());

                        processor.started(methodDescriptor, new TestStartEvent(toEpochMilli(suiteStartTime), suiteDescriptor.getId()));

                        // Cannot switch on types ...
                        if (testCase.getStatus() instanceof TestCaseSuccess) {
                            TestCaseSuccess success = (TestCaseSuccess) testCase.getStatus();
                            Optional.of(success.getStdout()).ifPresent(stdout -> processor.output(
                                    methodDescriptor.getId(),
                                    new DefaultTestOutputEvent(TestOutputEvent.Destination.StdOut, stdout)
                            ));
                            Optional.of(success.getStderr()).ifPresent(stderr -> processor.output(
                                    methodDescriptor.getId(),
                                    new DefaultTestOutputEvent(TestOutputEvent.Destination.StdErr, stderr)
                            ));
                            processor.completed(
                                    methodDescriptor.getId(),
                                    new TestCompleteEvent(
                                            toEpochMilli(testCase.endTime(suiteStartTime)),
                                            TestResult.ResultType.SUCCESS
                                    )
                            );
                        } else if (testCase.getStatus() instanceof TestCaseFailure) {
                            TestCaseFailure failure = (TestCaseFailure) testCase.getStatus();
                            processor.failure(
                                    methodDescriptor.getId(),
                                    org.gradle.api.tasks.testing.TestFailure.fromTestFrameworkFailure(
                                        new ExternalTestFailureException(
                                                "Test case being imported failed (" + Optional.ofNullable(failure.getType()).orElse("Untyped") + "): " +
                                                Optional.ofNullable(failure.getMessage()).orElse("") + " " +
                                                Optional.ofNullable(failure.getDescription()).orElse("")
                                        )
                                    )
                            );
                        } else if (testCase.getStatus() instanceof TestCaseError) {
                            TestCaseError error = (TestCaseError) testCase.getStatus();
                            processor.failure(
                                    methodDescriptor.getId(),
                                    org.gradle.api.tasks.testing.TestFailure.fromTestFrameworkFailure(
                                        new ExternalTestFailureException(
                                                "Test case being imported failed (" + Optional.ofNullable(error.getType()).orElse("Untyped") + "): " +
                                                Optional.ofNullable(error.getMessage()).orElse("") +
                                                Optional.ofNullable(error.getDescription()).map(desc -> "\n" + desc).orElse("")
                                        )
                                    )
                            );
                        } else if (testCase.getStatus() instanceof TestCaseSkipped) {
                            TestCaseSkipped skipped = (TestCaseSkipped) testCase.getStatus();
                            Optional.ofNullable(skipped.getMessage()).ifPresent(message -> processor.output(
                                    methodDescriptor.getId(),
                                    new DefaultTestOutputEvent(TestOutputEvent.Destination.StdOut, message)
                            ));
                            processor.completed(
                                    methodDescriptor.getId(),
                                    new TestCompleteEvent(toEpochMilli(testCase.endTime(suiteStartTime)), TestResult.ResultType.SKIPPED)
                            );
                        }
                    });
                    processor.completed(suiteDescriptor.getId(), new TestCompleteEvent(toEpochMilli(testSuite.endTime(() -> suiteStartTime))));
                });
    }


    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    @Override
    public void stopNow() {
    }

}
