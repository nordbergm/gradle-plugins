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
package co.elastic.gradle.dockerbase.lockfile;

import co.elastic.gradle.dockerbase.OSDistribution;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.gradle.api.tasks.Input;

import java.io.Serializable;

public final class UnchangingPackage implements Serializable {

    private final String name;
    private final String version;
    private final String release;
    private final String architecture;

    public UnchangingPackage(
            String name,
            String version,
            String release,
            String architecture
    ) {

        this.name = name;
        this.version = version;
        this.release = release;
        this.architecture = architecture;
    }

    @Input
    public String getName() {
        return name;
    }

    @Input
    public String getVersion() {
        return version;
    }

    @Input
    public String getRelease() {
        return release;
    }

    @Input
    public String getArchitecture() {
        return architecture;
    }

    public String getPackageName(OSDistribution distribution) {
        switch (distribution) {
            case CENTOS:
                return String.format("%s-%s-%s.%s", name, version, release, architecture);
            case UBUNTU:
            case DEBIAN:
                return String.format(
                        "%s=%s%s",
                        name,
                        version,
                        release == null || release.isEmpty() ? "" : "-" + release
                );
            default:
                throw new IllegalArgumentException();
        }
    }
}
