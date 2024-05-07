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

public final class TestCaseFailure implements TestCaseStatus {
    private final String message;
    private final String type;
    private final String description;

    public TestCaseFailure(
            String message,
            String type,
            String description
    ) {
        this.message = message;
        this.type = type;
        this.description = description;
    }

    public String getMessage() {
        return message;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }
}
