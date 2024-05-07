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
package co.elastic.gradle.utils.docker.instruction;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

public final class HealthCheck implements ContainerImageBuildInstruction {

    private final String cmd;
    private final String interval;
    private final String timeout;
    private final String startPeriod;
    private final Integer retries;

    public HealthCheck(String cmd, String interval, String timeout,
                       String startPeriod,
                       Integer retries) {

        this.cmd = cmd;
        this.interval = interval;
        this.timeout = timeout;
        this.startPeriod = startPeriod;
        this.retries = retries;
    }

    @Input
    public String getCmd() {
        return cmd;
    }

    @Input
    @Optional
    public String getInterval() {
        return interval;
    }

    @Input
    @Optional
    public String getTimeout() {
        return timeout;
    }

    @Input
    @Optional
    public String getStartPeriod() {
        return startPeriod;
    }

    @Input
    @Optional
    public Integer getRetries() {
        return retries;
    }
}
