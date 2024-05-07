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
import java.util.Optional;

public final class TestCase {
    private final String name;
    private final String className;
    private final Double time;
    private final TestCaseStatus status;

    public TestCase(
            String name,
            String className,
            Double time,
            TestCaseStatus status) {

        this.name = name;
        this.className = className;
        this.time = time;
        this.status = status;
    }

    public LocalDateTime endTime(LocalDateTime suiteStartTime) {
        return suiteStartTime
                .plusSeconds(Optional.of(time).orElse(0.0).longValue() * 1000 * 1000);
    }

    public String getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public TestCaseStatus getStatus() {
        return status;
    }
}
