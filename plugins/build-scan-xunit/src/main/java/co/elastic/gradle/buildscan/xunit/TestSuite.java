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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class TestSuite {
    private final String name;
    private final Integer errors;
    private final Integer failures;
    private final Integer skipped;
    private final LocalDateTime timestamp;
    private final Double time;
    private final List<TestCase> tests;

    public TestSuite(
            String name,
            Integer errors,
            Integer failures,
            Integer skipped,
            LocalDateTime timestamp,
            Double time,
            List<TestCase> tests
    ) {
        this.name = name;
        this.errors = errors;
        this.failures = failures;
        this.skipped = skipped;
        this.timestamp = timestamp;
        this.time = time;
        this.tests = tests;
    }

    public LocalDateTime startedTime(Supplier<LocalDateTime> defaultStartTime) {
        return Optional.ofNullable(timestamp).orElseGet(defaultStartTime);
    }

    public LocalDateTime endTime(Supplier<LocalDateTime> defaultStartTime) {
        Double convertedTime = Optional.of(this.time).orElse(0.0) * 1000 * 1000;
        return startedTime(defaultStartTime)
                .plusSeconds(convertedTime.longValue());
    }

    public String getName() {
        return name;
    }

    public Integer getErrors() {
        return errors;
    }

    public Integer getFailures() {
        return failures;
    }

    public Integer getSkipped() {
        return skipped;
    }

    public List<TestCase> getTests() {
        return tests;
    }
}
